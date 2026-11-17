package com.algofuture.bots.liquiditysweep.infrastructure.rest;

import com.algofuture.bots.liquiditysweep.application.port.in.RunDailyBacktestUseCase;
import com.algofuture.bots.liquiditysweep.domain.model.ConvictionConfig;
import com.algofuture.bots.liquiditysweep.domain.model.FootprintRules;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.shared.infrastructure.rest.CorsHelper;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Strategies 4.0 and 4.1, one trade per day picked by conviction:
 *
 * GET  /bots/liquidity-sweep/daily?from=...&to=...           → dry run (no writes)
 * POST /bots/liquidity-sweep/daily/journal?from=...&to=...   → picked trades recorded in strategy_trade
 *
 * Defaults: symbols=NQ.FUT,ES.FUT, interval=5m, liquidity = overnight session/day/week + overnight 1h + first
 * 15 minutes + intraday 5m swings, failed breakouts swept from 08:30 until lastSweep=14:00, rewardRisk=3.0,
 * flat at 15:00. strictUntil=10:00: before it a sweep needs earlyReasons=2 of the 3 reasons, after it
 * lateReasons=1 (see {@link ConvictionConfig}).
 *
 * footprint=true runs 4.1: a thin extreme in the excursion's footprint is a 4th reason (thinExtreme=0.19, see
 * {@link FootprintRules}); the same earlyReasons/lateReasons apply.
 *
 * stopBuffer=ES:8 (needs footprint=true) runs 4.2: on each listed contract the stop goes that many ticks beyond the
 * sweep wick and the target follows at rewardRisk of the wider risk. Empty (default) = stop at the wick.
 *
 * maxRiskAtr=3.0 (needs a stopBuffer) runs 4.3: a sweep whose stop at the wick is wider than that many times the mean
 * range of the 24 candles before its confirmation does not qualify (see {@link ConvictionConfig}). 0 (default) = off.
 */
@Path("/liquidity-sweep/daily")
@Produces(MediaType.APPLICATION_JSON)
public class DailySweepResource {

    /** Query params shared by the dry run and the journal endpoint. */
    public static class Query {
        @QueryParam("symbols") @DefaultValue("NQ.FUT,ES.FUT") public String symbols;
        @QueryParam("from") public String from;
        @QueryParam("to") public String to;
        @QueryParam("interval") @DefaultValue("5m") public String interval;
        @QueryParam("lastSweep") @DefaultValue("14:00") public String lastSweep;
        @QueryParam("rewardRisk") @DefaultValue("3.0") public double rewardRisk;
        @QueryParam("openingRange") @DefaultValue("15") public int openingRange;
        @QueryParam("strictUntil") @DefaultValue("10:00") public String strictUntil;
        @QueryParam("earlyReasons") @DefaultValue("2") public int earlyReasons;
        @QueryParam("lateReasons") @DefaultValue("1") public int lateReasons;
        @QueryParam("correlationMinutes") @DefaultValue("45") public int correlationMinutes;
        @QueryParam("swingStrength") @DefaultValue("3") public int swingStrength;
        @QueryParam("footprint") @DefaultValue("false") public boolean footprint;
        @QueryParam("thinExtreme") @DefaultValue("0.19") public double thinExtreme;
        /** "ES:8" or "ES:8,NQ:0": ticks beyond the wick per contract root. */
        @QueryParam("stopBuffer") @DefaultValue("") public String stopBuffer;
        @QueryParam("maxRiskAtr") @DefaultValue("0") public double maxRiskAtr;
    }

    private final RunDailyBacktestUseCase useCase;

    public DailySweepResource(RunDailyBacktestUseCase useCase) {
        this.useCase = useCase;
    }

    @GET
    public Response preview(@BeanParam Query q) {
        return run(q, false);
    }

    @POST
    @Path("/journal")
    public Response journal(@BeanParam Query q) {
        return run(q, true);
    }

    private static Map<String, Integer> stopBuffers(String spec) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String part : spec.split(",")) {
            if (part.isBlank()) continue;
            String[] kv = part.trim().split(":");
            if (kv.length != 2) throw new IllegalArgumentException("stopBuffer must look like ES:8,NQ:0, got: " + part);
            out.put(kv[0].trim().toUpperCase(Locale.ROOT), Integer.parseInt(kv[1].trim()));
        }
        return out;
    }

    private Response run(Query q, boolean journal) {
        try {
            if (q.from == null || q.to == null) {
                throw new IllegalArgumentException("'from' and 'to' are required (ISO-8601 with offset)");
            }
            SweepDetectorConfig d = SweepDetectorConfig.DEFAULT;
            SweepDetectorConfig config = new SweepDetectorConfig(d.minPenetrationTicks(), d.minExcursionBars(),
                    d.reclaimBars(), d.confirmationBars(), q.rewardRisk, 0, d.sessionStart(), d.sessionEnd(),
                    LocalTime.parse(q.lastSweep), ZoneId.of("America/Chicago"), true, true, q.openingRange);
            ConvictionConfig conviction = new ConvictionConfig(LocalTime.parse(q.strictUntil), q.earlyReasons,
                    q.lateReasons, Duration.ofMinutes(q.correlationMinutes), q.swingStrength,
                    q.footprint ? new FootprintRules(q.thinExtreme) : null, stopBuffers(q.stopBuffer), q.maxRiskAtr);
            List<String> symbols = Arrays.stream(q.symbols.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            return CorsHelper.apply(Response.ok(useCase.run(symbols, OffsetDateTime.parse(q.from), OffsetDateTime.parse(q.to),
                    q.interval, config, conviction, journal))).build();
        } catch (IllegalArgumentException | DateTimeException e) {
            return CorsHelper.apply(Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage())).build();
        }
    }
}
