package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.FootprintRules;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDirection;
import com.algofuture.bots.liquiditysweep.domain.service.ConvictionSelector.Reason;
import com.algofuture.shared.marketdata.domain.model.FootprintLevel;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Strategy 4.1's order-flow reason for one sweep, from the footprint of every 1m candle of its excursion (pierce
 * candle → reclaim candle): the minutes known when the sweep confirms, so nothing looks ahead.
 *
 * {@link Reason#THIN_EXTREME}: the two most extreme prices of the excursion traded very little next to an average
 * price of it. The breakout side ran out of traders out there: a finished auction, which tends to reverse.
 */
public final class FootprintReasons {

    private static final int EXTREME_PRICES = 2;

    private FootprintReasons() {
    }

    /** @param minutes 1m candles WITH footprint covering the excursion, any order */
    public static List<Reason> of(LiquiditySweep sweep, List<TradesBar1m> minutes, double tickSize, FootprintRules rules) {
        // price in ticks → volume traded there, merged over the excursion
        TreeMap<Long, Long> volume = new TreeMap<>();
        for (TradesBar1m m : minutes) {
            for (Map.Entry<Double, FootprintLevel> e : m.footprint().entrySet()) {
                volume.merge(Math.round(e.getKey() / tickSize), e.getValue().totalVolume(), Long::sum);
            }
        }
        if (volume.isEmpty()) return List.of();
        double average = volume.values().stream().mapToLong(Long::longValue).sum() / (double) volume.size();
        boolean bullish = sweep.direction() == SweepDirection.BULLISH;   // a swept low: its extreme is the bottom
        long extreme = (bullish ? volume : volume.descendingMap()).values().stream()
                .limit(EXTREME_PRICES).mapToLong(Long::longValue).sum();
        return extreme <= rules.thinExtremeRatio() * Math.max(average, 1) ? List.of(Reason.THIN_EXTREME) : List.of();
    }
}
