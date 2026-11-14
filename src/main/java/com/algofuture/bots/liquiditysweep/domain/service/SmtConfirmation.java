package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDirection;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;

import java.util.List;

/**
 * Correlated-market confirmation: while one index sweeps a level, did the other one also trade through its
 * own level of the same type (its own PDL for a PDL sweep...)? In the Jun–Nov 2026 backtest sweeps that both
 * NQ and ES made together held far better than lone ones, and a divergence (only one sweeps) lost money.
 */
public final class SmtConfirmation {

    private SmtConfirmation() {
    }

    /**
     * @param otherBars   the other contract's candles, same interval, ascending
     * @param otherLevels the other contract's levels for the sweep's day, including those already crossed
     *                    before the open ({@link KeyLevelCalculator#rawLevelsByDay})
     */
    public static boolean confirmed(LiquiditySweep sweep, List<TradesBar1m> otherBars, List<KeyLevel> otherLevels) {
        boolean bullish = sweep.direction() == SweepDirection.BULLISH;
        List<KeyLevel> sameType = otherLevels.stream().filter(l -> l.type() == sweep.levelType()).toList();
        if (sameType.isEmpty()) return false;
        for (TradesBar1m b : otherBars) {
            if (b.tsEvent().isBefore(sweep.piercedAt())) continue;
            if (b.tsEvent().isAfter(sweep.sweepAt())) break;
            for (KeyLevel l : sameType) {
                if (bullish ? b.low() < l.price() : b.high() > l.price()) return true;
            }
        }
        return false;
    }
}
