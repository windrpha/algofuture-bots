package com.algofuture.bots.liquiditysweep.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Tuning of the liquidity-sweep strategy.
 *
 * @param minPenetrationTicks minimum ticks the sweep wick must trade beyond the level
 *                            (tick size from {@code instrument})
 * @param minExcursionBars    minimum candles from pierce to reclaim, counting both. 2 = the pierce candle
 *                            must CLOSE beyond the level (a failed breakout that traps breakout traders);
 *                            1 also accepts a lone wick that closes back on the same candle
 * @param reclaimBars         candles allowed to close back across a pierced level, counting the pierce
 *                            candle (1 = the pierce candle itself must close back)
 * @param confirmationBars    candles after the sweep allowed to confirm it (0 = the sweep is
 *                            CONFIRMED on its own close)
 * @param rewardRisk          target distance as a multiple of the entry→stop risk
 * @param maxHoldBars         time exit after this many candles in the trade (0 = hold until stop/target)
 * @param sessionStart        trading window start: sweeps (and confirmations) only from here on;
 *                            it is also where the overnight session used for ONH/ONL ends
 * @param sessionEnd          trading window end (exclusive)
 * @param lastSweepTime       sweeps whose reclaim candle starts at/after this time are ignored (the level is
 *                            still consumed); {@code null} = until {@code sessionEnd}
 * @param zone                time zone of the session times
 * @param flatAtSessionClose  exit any open trade at the open of the first candle after {@code sessionEnd}
 * @param hourLevels          also treat the overnight 1h highs/lows still untouched at the session start as
 *                            liquidity (strategy 2.1), not only the session / day / week extremes (2.0)
 * @param openingRangeMinutes also treat the high/low of the first N minutes of the trading window as
 *                            liquidity, from the moment the range closes (0 = off). On NQ Jun–Nov 2026 failed
 *                            breakouts of the 30 min opening range reversed often enough to fade
 */
public record SweepDetectorConfig(int minPenetrationTicks, int minExcursionBars, int reclaimBars, int confirmationBars,
                                  double rewardRisk, int maxHoldBars, LocalTime sessionStart, LocalTime sessionEnd,
                                  LocalTime lastSweepTime, ZoneId zone, boolean flatAtSessionClose,
                                  boolean hourLevels, int openingRangeMinutes) {

    /**
     * 08:30–15:00 Chicago = the NY regular session (09:30–16:00 ET). Failed breakouts only (pierce candle
     * closes beyond the level), swept before 11:00, flat at the close: in the Jun–Nov 2026 backtest
     * one-candle wicks and late-session sweeps lost money on NQ, ES and YM.
     */
    public static final SweepDetectorConfig DEFAULT = new SweepDetectorConfig(1, 2, 3, 3, 2.0, 0,
            LocalTime.of(8, 30), LocalTime.of(15, 0), LocalTime.of(11, 0), ZoneId.of("America/Chicago"), true, false, 0);

    /**
     * Strategies 4.x (one trade per day by conviction): overnight 1h highs/lows and the first 15 minutes are liquidity
     * too, sweeps until 14:00, 3R target, flat at 15:00. Same defaults as the /daily endpoint.
     */
    public static final SweepDetectorConfig DAILY = new SweepDetectorConfig(1, 2, 3, 3, 3.0, 0,
            LocalTime.of(8, 30), LocalTime.of(15, 0), LocalTime.of(14, 0), ZoneId.of("America/Chicago"), true, true, 15);

    public SweepDetectorConfig {
        if (minPenetrationTicks < 0) throw new IllegalArgumentException("minPenetrationTicks must be >= 0");
        if (reclaimBars < 1) throw new IllegalArgumentException("reclaimBars must be >= 1");
        if (minExcursionBars < 1 || minExcursionBars > reclaimBars) {
            throw new IllegalArgumentException("minExcursionBars must be between 1 and reclaimBars");
        }
        if (confirmationBars < 0) throw new IllegalArgumentException("confirmationBars must be >= 0");
        if (rewardRisk <= 0) throw new IllegalArgumentException("rewardRisk must be > 0");
        if (maxHoldBars < 0) throw new IllegalArgumentException("maxHoldBars must be >= 0");
        if (!sessionStart.isBefore(sessionEnd)) throw new IllegalArgumentException("sessionStart must be before sessionEnd");
        if (openingRangeMinutes < 0) throw new IllegalArgumentException("openingRangeMinutes must be >= 0");
    }

    /** Without opening-range levels (strategies 2.0, 2.1 and 3.0). */
    public SweepDetectorConfig(int minPenetrationTicks, int minExcursionBars, int reclaimBars, int confirmationBars,
                               double rewardRisk, int maxHoldBars, LocalTime sessionStart, LocalTime sessionEnd,
                               LocalTime lastSweepTime, ZoneId zone, boolean flatAtSessionClose, boolean hourLevels) {
        this(minPenetrationTicks, minExcursionBars, reclaimBars, confirmationBars, rewardRisk, maxHoldBars,
                sessionStart, sessionEnd, lastSweepTime, zone, flatAtSessionClose, hourLevels, 0);
    }

    /** End of the opening range of a day (only meaningful when {@code openingRangeMinutes > 0}). */
    public Instant openingRangeEnd(LocalDate day) {
        return sessionStart(day).plusSeconds(60L * openingRangeMinutes);
    }

    public Instant sessionStart(LocalDate day) {
        return ZonedDateTime.of(day, sessionStart, zone).toInstant();
    }

    public Instant sessionEnd(LocalDate day) {
        return ZonedDateTime.of(day, sessionEnd, zone).toInstant();
    }

    /** Trading day of an instant, in the window's zone. */
    public LocalDate dayOf(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }

    /** Name in the {@code strategy} catalog: the rule set version this configuration runs. */
    public String strategyName() {
        return hourLevels ? "liquidity sweep 2.1" : "liquidity sweep 2.0";
    }

    /** Whether a sweep reclaimed on a candle starting at {@code instant} can still be traded. */
    public boolean beforeLastSweep(Instant instant) {
        return lastSweepTime == null || instant.atZone(zone).toLocalTime().isBefore(lastSweepTime);
    }

    public boolean inSession(Instant instant) {
        LocalDate day = dayOf(instant);
        return !instant.isBefore(sessionStart(day)) && instant.isBefore(sessionEnd(day));
    }
}
