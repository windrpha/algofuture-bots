package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;

import java.time.Instant;
import java.util.List;

/**
 * Stop width against recent volatility (strategy 4.3): the sweep's entry→stop distance over the mean range of the
 * {@link #ATR_BARS} candles before its confirmation candle. A stop many times wider than the market has been moving
 * means the entry came at the end of a violent reversal candle, chasing it, with the stop at the bottom of the whole
 * move. On NQ/ES Feb–Nov 2026 the widest third of stops (above ~2.75 ATR) made ~0R per trade in both halves of the
 * sample, while the rest made money.
 */
public final class StopWidth {

    /** Candles averaged for the ATR: 2 hours of 5m candles, overnight ones included before the open. */
    public static final int ATR_BARS = 24;

    private StopWidth() {
    }

    /**
     * Risk at the wick (before any {@link StopBuffer}) in ATRs, or {@code NaN} when the confirmation candle is not in
     * {@code bars} or has no candle before it.
     *
     * @param bars candles of the sweep's contract, ascending, same interval as detection
     */
    public static double riskOverAtr(LiquiditySweep sweep, List<TradesBar1m> bars) {
        if (sweep.entry() == null) return Double.NaN;
        int confirm = indexOf(bars, sweep.confirmedAt());
        if (confirm <= 0) return Double.NaN;
        double range = 0;
        int from = Math.max(0, confirm - ATR_BARS);
        for (int i = from; i < confirm; i++) {
            range += bars.get(i).high() - bars.get(i).low();
        }
        double atr = range / (confirm - from);
        return atr > 0 ? Math.abs(sweep.entry() - sweep.stop()) / atr : Double.NaN;
    }

    private static int indexOf(List<TradesBar1m> bars, Instant t) {
        int lo = 0, hi = bars.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = bars.get(mid).tsEvent().compareTo(t);
            if (cmp == 0) return mid;
            if (cmp < 0) lo = mid + 1; else hi = mid - 1;
        }
        return -1;
    }
}
