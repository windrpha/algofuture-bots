package com.algofuture.bots.liquiditysweep.infrastructure.rest;

import com.algofuture.bots.liquiditysweep.application.port.in.RunSessionBacktestUseCase;
import com.algofuture.bots.liquiditysweep.domain.model.SessionConfig;
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
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/**
 * Strategy 3.0, one trade per session across correlated contracts:
 *
 * GET  /bots/liquidity-sweep/session?from=...&to=...           → dry run (no writes)
 * POST /bots/liquidity-sweep/session/journal?from=...&to=...   → picked trades recorded in strategy_trade
 *
 * Defaults: symbols=NQ.FUT,ES.FUT, window=10:00 (sweeps and entries from the 08:30 open until then),
 * smt=true, interval=5m, rewardRisk=2.0, minExcursionBars=2, reclaimBars=3, confirmationBars=3,
 * minPenetrationTicks=1, flatAtClose=true, openingRange=0 (30 → strategy 3.1: the 08:30–09:00 high/low is
 * one more level), rangeTarget=false (true → strategy 3.2: take profit at the opposite side of that range
 * when it is at least 1R away, else at rewardRisk).
 */
@Path("/liquidity-sweep/session")
@Produces(MediaType.APPLICATION_JSON)
public class SessionSweepResource {

    /** Query params shared by the dry run and the journal endpoint. */
    public static class Query {
        @QueryParam("symbols") @DefaultValue("NQ.FUT,ES.FUT") public String symbols;
        @QueryParam("from") public String from;
        @QueryParam("to") public String to;
        @QueryParam("interval") @DefaultValue("5m") public String interval;
        @QueryParam("window") @DefaultValue("10:00") public String window;
        @QueryParam("smt") @DefaultValue("true") public boolean smt;
        @QueryParam("rewardRisk") @DefaultValue("2.0") public double rewardRisk;
        @QueryParam("minExcursionBars") @DefaultValue("2") public int minExcursionBars;
        @QueryParam("reclaimBars") @DefaultValue("3") public int reclaimBars;
        @QueryParam("confirmationBars") @DefaultValue("3") public int confirmationBars;
        @QueryParam("minPenetrationTicks") @DefaultValue("1") public int minPenetrationTicks;
        @QueryParam("flatAtClose") @DefaultValue("true") public boolean flatAtClose;
        /** Minutes of opening range traded as liquidity: 0 = strategy 3.0, e.g. 30 = strategy 3.1. */
        @QueryParam("openingRange") @DefaultValue("0") public int openingRange;
        /** Target the opposite side of the opening range when ≥1R away (strategy 3.2, needs openingRange). */
        @QueryParam("rangeTarget") @DefaultValue("false") public boolean rangeTarget;
    }

    private final RunSessionBacktestUseCase useCase;

    public SessionSweepResource(RunSessionBacktestUseCase useCase) {
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

    private Response run(Query q, boolean journal) {
        try {
            if (q.from == null || q.to == null) {
                throw new IllegalArgumentException("'from' and 'to' are required (ISO-8601 with offset)");
            }
            LocalTime window = LocalTime.parse(q.window);
            SweepDetectorConfig d = SweepDetectorConfig.DEFAULT;
            // the window bounds both the sweep (reclaim candle) and the entry
            SweepDetectorConfig config = new SweepDetectorConfig(q.minPenetrationTicks, q.minExcursionBars, q.reclaimBars,
                    q.confirmationBars, q.rewardRisk, 0, d.sessionStart(), d.sessionEnd(), window, ZoneId.of("America/Chicago"),
                    q.flatAtClose, false, q.openingRange);
            List<String> symbols = Arrays.stream(q.symbols.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            return CorsHelper.apply(Response.ok(useCase.run(symbols, OffsetDateTime.parse(q.from), OffsetDateTime.parse(q.to),
                    q.interval, config, new SessionConfig(window, q.smt, q.rangeTarget), journal))).build();
        } catch (IllegalArgumentException | DateTimeException e) {
            return CorsHelper.apply(Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage())).build();
        }
    }
}
