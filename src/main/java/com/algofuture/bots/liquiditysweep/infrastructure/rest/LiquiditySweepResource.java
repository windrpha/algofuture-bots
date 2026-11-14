package com.algofuture.bots.liquiditysweep.infrastructure.rest;

import com.algofuture.bots.liquiditysweep.application.port.in.RunLiquiditySweepBacktestUseCase;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.shared.infrastructure.rest.CorsHelper;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * GET  /bots/liquidity-sweep/{symbol}?from=...&to=...   → dry run: sweeps, trades and summary (no writes)
 * POST /bots/liquidity-sweep/{symbol}/journal?from=...  → same run, trades recorded in strategy_trade
 *
 * {symbol} is the trade_1m_live symbol (e.g. NQ.FUT). Optional tuning: interval=5m, minPenetrationTicks=1,
 * minExcursionBars=2, reclaimBars=3, confirmationBars=3, rewardRisk=2.0, maxHoldBars=0, sessionStart=08:30,
 * sessionEnd=15:00, lastSweep=11:00 (empty = no cutoff), zone=America/Chicago, flatAtClose=true,
 * hourLevels=false (true = strategy 2.1: overnight 1h highs/lows are liquidity too).
 */
@Path("/liquidity-sweep")
@Produces(MediaType.APPLICATION_JSON)
public class LiquiditySweepResource {

    private final RunLiquiditySweepBacktestUseCase useCase;

    public LiquiditySweepResource(RunLiquiditySweepBacktestUseCase useCase) {
        this.useCase = useCase;
    }

    @GET
    @Path("/{symbol}")
    public Response preview(@PathParam("symbol") String symbol,
                            @QueryParam("from") String from,
                            @QueryParam("to") String to,
                            @QueryParam("interval") @DefaultValue("5m") String interval,
                            @QueryParam("minPenetrationTicks") @DefaultValue("1") int minPenetrationTicks,
                            @QueryParam("minExcursionBars") @DefaultValue("2") int minExcursionBars,
                            @QueryParam("reclaimBars") @DefaultValue("3") int reclaimBars,
                            @QueryParam("confirmationBars") @DefaultValue("3") int confirmationBars,
                            @QueryParam("rewardRisk") @DefaultValue("2.0") double rewardRisk,
                            @QueryParam("maxHoldBars") @DefaultValue("0") int maxHoldBars,
                            @QueryParam("sessionStart") @DefaultValue("08:30") String sessionStart,
                            @QueryParam("sessionEnd") @DefaultValue("15:00") String sessionEnd,
                            @QueryParam("zone") @DefaultValue("America/Chicago") String zone,
                            @QueryParam("lastSweep") @DefaultValue("11:00") String lastSweep,
                            @QueryParam("flatAtClose") @DefaultValue("true") boolean flatAtClose,
                            @QueryParam("hourLevels") @DefaultValue("false") boolean hourLevels) {
        return run(symbol, from, to, interval,
                new Window(sessionStart, sessionEnd, lastSweep, zone, flatAtClose, hourLevels),
                minPenetrationTicks, minExcursionBars, reclaimBars, confirmationBars, rewardRisk, maxHoldBars, false);
    }

    @POST
    @Path("/{symbol}/journal")
    public Response journal(@PathParam("symbol") String symbol,
                            @QueryParam("from") String from,
                            @QueryParam("to") String to,
                            @QueryParam("interval") @DefaultValue("5m") String interval,
                            @QueryParam("minPenetrationTicks") @DefaultValue("1") int minPenetrationTicks,
                            @QueryParam("minExcursionBars") @DefaultValue("2") int minExcursionBars,
                            @QueryParam("reclaimBars") @DefaultValue("3") int reclaimBars,
                            @QueryParam("confirmationBars") @DefaultValue("3") int confirmationBars,
                            @QueryParam("rewardRisk") @DefaultValue("2.0") double rewardRisk,
                            @QueryParam("maxHoldBars") @DefaultValue("0") int maxHoldBars,
                            @QueryParam("sessionStart") @DefaultValue("08:30") String sessionStart,
                            @QueryParam("sessionEnd") @DefaultValue("15:00") String sessionEnd,
                            @QueryParam("zone") @DefaultValue("America/Chicago") String zone,
                            @QueryParam("lastSweep") @DefaultValue("11:00") String lastSweep,
                            @QueryParam("flatAtClose") @DefaultValue("true") boolean flatAtClose,
                            @QueryParam("hourLevels") @DefaultValue("false") boolean hourLevels) {
        return run(symbol, from, to, interval,
                new Window(sessionStart, sessionEnd, lastSweep, zone, flatAtClose, hourLevels),
                minPenetrationTicks, minExcursionBars, reclaimBars, confirmationBars, rewardRisk, maxHoldBars, true);
    }

    /** Trading-window query params, grouped to keep {@link #run} readable. */
    private record Window(String start, String end, String lastSweep, String zone, boolean flatAtClose,
                          boolean hourLevels) {}

    private Response run(String symbol, String from, String to, String interval, Window window,
                         int minPenetrationTicks, int minExcursionBars, int reclaimBars, int confirmationBars,
                         double rewardRisk, int maxHoldBars, boolean journal) {
        try {
            if (from == null || to == null) {
                throw new IllegalArgumentException("'from' and 'to' are required (ISO-8601 with offset)");
            }
            SweepDetectorConfig config = new SweepDetectorConfig(minPenetrationTicks, minExcursionBars, reclaimBars,
                    confirmationBars, rewardRisk, maxHoldBars, LocalTime.parse(window.start()), LocalTime.parse(window.end()),
                    window.lastSweep().isBlank() ? null : LocalTime.parse(window.lastSweep()), ZoneId.of(window.zone()),
                    window.flatAtClose(), window.hourLevels());
            return CorsHelper.apply(Response.ok(useCase.run(symbol,
                    OffsetDateTime.parse(from), OffsetDateTime.parse(to), interval, config, journal))).build();
        } catch (IllegalArgumentException | DateTimeException e) {
            return CorsHelper.apply(Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage())).build();
        }
    }
}
