package com.algofuture.bots.liquiditysweep.domain.model;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;

/**
 * One trade per day picked by conviction (strategy 4.0). Each confirmed sweep earns one "reason" for
 * each of: a key level (not an intraday swing), the correlated index already swept the same way shortly
 * before, and the trade goes the same way as the opening gap.
 *
 * @param strictUntil        before this entry time (zone of the sweep config) a sweep needs
 *                           {@code earlyReasons}; from then on {@code lateReasons} is enough
 * @param earlyReasons       reasons required in the opening hour and a half, the noisiest part of the session
 * @param lateReasons        reasons required afterwards
 * @param correlationWindow  how long before the entry the other index's sweep (its reclaim candle close) counts
 * @param swingStrength      candles on each side that make an intraday swing extreme
 * @param footprint          order-flow reason (strategy 4.1); {@code null} = price-only reasons (4.0)
 * @param stopBufferTicks    per contract root ("ES"), ticks the stop goes beyond the sweep wick (strategy 4.2, see
 *                           {@link com.algofuture.bots.liquiditysweep.domain.service.StopBuffer}); empty = at the wick
 * @param maxRiskAtr         a sweep whose stop (at the wick) is wider than this many ATRs does not qualify (strategy
 *                           4.3, see {@link com.algofuture.bots.liquiditysweep.domain.service.StopWidth}); 0 = off
 */
public record ConvictionConfig(LocalTime strictUntil, int earlyReasons, int lateReasons, Duration correlationWindow,
                               int swingStrength, FootprintRules footprint, Map<String, Integer> stopBufferTicks,
                               double maxRiskAtr) {

    /**
     * On NQ/ES Jun–Nov 2026 this kept 70 of ~105 sessions at +0.38R per trade after costs, with both halves of
     * the sample (Jun–Aug, Sep–Nov) positive on their own.
     */
    public static final ConvictionConfig DEFAULT = new ConvictionConfig(LocalTime.of(10, 0), 2, 1, Duration.ofMinutes(45), 3,
            null, Map.of());

    /**
     * Strategy 4.1: the same three reasons plus a thin extreme in the footprint, still 2 needed before 10:00 and 1
     * after. Same sample: 77 sessions at +0.45R per trade after costs (4.0: +0.35R in the same engine), both halves
     * positive; most of the gain comes from ~13 extra trades, so treat it as a modest improvement.
     */
    public static final ConvictionConfig WITH_FOOTPRINT = new ConvictionConfig(LocalTime.of(10, 0), 2, 1,
            Duration.ofMinutes(45), 3, FootprintRules.DEFAULT, Map.of());

    /**
     * Strategy 4.2: 4.1 with ES stops 8 ticks beyond the wick (NQ stays at the wick). In the research engine it took
     * ES from +0.26R to +0.56R per trade (both halves improved) and the whole run from ~+34R to ~+46R after costs.
     */
    public static final ConvictionConfig WITH_STOP_BUFFER = new ConvictionConfig(LocalTime.of(10, 0), 2, 1,
            Duration.ofMinutes(45), 3, FootprintRules.DEFAULT, Map.of("ES", 8));

    /**
     * Strategy 4.3: 4.2 skipping sweeps whose stop is wider than 3 ATRs (the day can still take a later one). In the
     * research engine, NQ/ES Feb–Nov 2026: 143 trades for ~+66R gross vs 154 for ~+54R, both halves better
     * (Feb–May +0.16R → +0.19R per trade, Jun–Nov +0.49R → +0.65R). Found after a loss it would have skipped
     * (2026-11-16, NQ long with a 4.1 ATR stop), so the Feb–May gain is the one to trust: modest.
     */
    public static final ConvictionConfig WITH_STOP_WIDTH = new ConvictionConfig(LocalTime.of(10, 0), 2, 1,
            Duration.ofMinutes(45), 3, FootprintRules.DEFAULT, Map.of("ES", 8), 3.0);

    /** Without a stop width limit (strategies 4.0 to 4.2). */
    public ConvictionConfig(LocalTime strictUntil, int earlyReasons, int lateReasons, Duration correlationWindow,
                            int swingStrength, FootprintRules footprint, Map<String, Integer> stopBufferTicks) {
        this(strictUntil, earlyReasons, lateReasons, correlationWindow, swingStrength, footprint, stopBufferTicks, 0);
    }

    public ConvictionConfig {
        if (earlyReasons < 0 || lateReasons < 0) throw new IllegalArgumentException("reasons must be >= 0");
        if (correlationWindow.isNegative()) throw new IllegalArgumentException("correlationWindow must be >= 0");
        if (swingStrength < 1) throw new IllegalArgumentException("swingStrength must be >= 1");
        stopBufferTicks = Map.copyOf(stopBufferTicks);
        if (stopBufferTicks.values().stream().anyMatch(t -> t < 0)) {
            throw new IllegalArgumentException("stopBufferTicks must be >= 0");
        }
        if (stopBufferTicks.values().stream().anyMatch(t -> t > 0) && footprint == null) {
            throw new IllegalArgumentException("a stop buffer is strategy 4.2, which builds on 4.1: it needs footprint");
        }
        if (maxRiskAtr < 0) throw new IllegalArgumentException("maxRiskAtr must be >= 0");
        if (maxRiskAtr > 0 && !hasStopBuffer(stopBufferTicks)) {
            throw new IllegalArgumentException("a stop width limit is strategy 4.3, which builds on 4.2: it needs a stop buffer");
        }
    }

    /** Name in the {@code strategy} catalog. */
    public String strategyName() {
        if (footprint == null) return "liquidity sweep 4.0";
        if (maxRiskAtr > 0) return "liquidity sweep 4.3";
        return hasStopBuffer(stopBufferTicks) ? "liquidity sweep 4.2" : "liquidity sweep 4.1";
    }

    private static boolean hasStopBuffer(Map<String, Integer> stopBufferTicks) {
        return stopBufferTicks.values().stream().anyMatch(t -> t > 0);
    }

    /** Ticks beyond the wick for a contract root ("ES", "NQ"), 0 when none is set. */
    public int stopBufferTicks(String root) {
        return stopBufferTicks.getOrDefault(root, 0);
    }

    /** Whether a stop of {@code riskAtr} ATRs is narrow enough; unknown ({@code NaN}) passes. */
    public boolean stopNarrowEnough(double riskAtr) {
        return maxRiskAtr <= 0 || Double.isNaN(riskAtr) || riskAtr <= maxRiskAtr;
    }

    public int requiredReasons(LocalTime entry) {
        return entry.isBefore(strictUntil) ? earlyReasons : lateReasons;
    }
}
