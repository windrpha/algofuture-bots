package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDirection;

import java.util.List;

/**
 * Session target (strategy 3.2): exit at the opposite side of the opening range (ORH for a long, ORL for a
 * short), where the liquidity of the other side rests, instead of a fixed multiple of the risk. When that side
 * is closer than {@link #MIN_REWARD_RISK} R, or already behind the entry, the fixed target is kept.
 */
public final class RangeTarget {

    /** Below this the trade risks more than it can make: keep the fixed rewardRisk target. */
    public static final double MIN_REWARD_RISK = 1.0;

    private RangeTarget() {
    }

    /**
     * @param dayLevels the sweep's day levels, including the opening range ones
     *                  ({@code openingRangeMinutes > 0}); without them the sweep is returned unchanged
     */
    public static LiquiditySweep apply(LiquiditySweep sweep, List<KeyLevel> dayLevels) {
        if (sweep.entry() == null) return sweep;
        boolean bullish = sweep.direction() == SweepDirection.BULLISH;
        KeyLevel.Type opposite = bullish ? KeyLevel.Type.OPENING_RANGE_HIGH : KeyLevel.Type.OPENING_RANGE_LOW;
        double entry = sweep.entry(), risk = Math.abs(entry - sweep.stop());
        return dayLevels.stream()
                .filter(l -> l.type() == opposite)
                .map(KeyLevel::price)
                .filter(p -> (bullish ? p - entry : entry - p) >= MIN_REWARD_RISK * risk)
                .findFirst()
                .map(p -> new LiquiditySweep(sweep.symbol(), sweep.direction(), sweep.status(), sweep.sweptLevel(),
                        sweep.levelType(), sweep.levelAt(), sweep.levelsSwept(), sweep.piercedAt(), sweep.sweepAt(),
                        sweep.sweepExtreme(), sweep.sweepClose(), sweep.confirmedAt(), sweep.entry(), sweep.stop(), p))
                .orElse(sweep);
    }
}
