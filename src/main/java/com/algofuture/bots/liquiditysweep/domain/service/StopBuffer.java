package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDirection;

/**
 * Stop beyond the sweep wick (strategy 4.2): moves the stop {@code buffer} further from the entry and re-derives the
 * target at the same rewardRisk of the wider risk. On ES Jun–Nov 2026, 1–2 point runs past the wick that then went
 * on to the target were common (8 ticks turned 4 of 22 losers into winners; 4–16 ticks all helped in both halves of
 * the sample). NQ's stops are ~4× wider in ticks and gained nothing, so the buffer is set per contract.
 */
public final class StopBuffer {

    private StopBuffer() {
    }

    /** @param buffer price distance added beyond the stop (0 returns the sweep unchanged) */
    public static LiquiditySweep apply(LiquiditySweep sweep, double buffer, double rewardRisk) {
        if (sweep.entry() == null || buffer == 0) return sweep;
        int sign = sweep.direction() == SweepDirection.BULLISH ? 1 : -1;
        double stop = sweep.stop() - sign * buffer;
        double target = sweep.entry() + sign * rewardRisk * Math.abs(sweep.entry() - stop);
        return new LiquiditySweep(sweep.symbol(), sweep.direction(), sweep.status(), sweep.sweptLevel(),
                sweep.levelType(), sweep.levelAt(), sweep.levelsSwept(), sweep.piercedAt(), sweep.sweepAt(),
                sweep.sweepExtreme(), sweep.sweepClose(), sweep.confirmedAt(), sweep.entry(), stop, target);
    }
}
