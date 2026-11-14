package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.FootprintRules;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel.Type;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDirection;
import com.algofuture.bots.liquiditysweep.domain.model.SweepStatus;
import com.algofuture.bots.liquiditysweep.domain.service.ConvictionSelector.Reason;
import com.algofuture.shared.marketdata.domain.model.FootprintLevel;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FootprintReasonsTest {

    private static final double TICK = 0.25;
    private static final FootprintRules RULES = FootprintRules.DEFAULT;
    private static final Instant T = Instant.parse("2026-11-11T15:00:00Z");

    private static LiquiditySweep sweep(SweepDirection dir) {
        return new LiquiditySweep("NQ.FUT", dir, SweepStatus.CONFIRMED, 100, Type.SESSION_LOW, T, List.of("ONL"),
                T, T, 99, 101, T, 102.0, 99.0, 111.0);
    }

    /** One minute whose footprint is {price, buyVolume, sellVolume} rows. */
    private static TradesBar1m minute(double[]... rows) {
        Map<Double, FootprintLevel> fp = new TreeMap<>();
        for (double[] r : rows) {
            fp.put(r[0], new FootprintLevel(r[0], (long) r[1], (long) r[2], 0, 0, new TreeMap<>()));
        }
        return new TradesBar1m(T, "NQ.FUT", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, fp);
    }

    // two lowest prices: 2 contracts in total; the excursion averages (1 + 1 + 50 + 80 + 34 + 25) / 6 ≈ 32 per price
    private static final List<TradesBar1m> THIN_BOTTOM = List.of(
            minute(new double[]{99.00, 0, 1}, new double[]{99.25, 1, 0},
                   new double[]{99.50, 20, 30}, new double[]{99.75, 25, 40}),
            minute(new double[]{99.75, 5, 10}, new double[]{100.00, 4, 30}, new double[]{100.25, 5, 20}));

    @Test
    void sweptLow_thinBottom_isAReason() {
        assertEquals(List.of(Reason.THIN_EXTREME), FootprintReasons.of(sweep(SweepDirection.BULLISH), THIN_BOTTOM, TICK, RULES));
    }

    @Test
    void sweptHigh_looksAtTheTop_heavyTopIsNoReason() {
        // the same excursion read as a swept high: its two highest prices traded 59 contracts, above average
        assertEquals(List.of(), FootprintReasons.of(sweep(SweepDirection.BEARISH), THIN_BOTTOM, TICK, RULES));
    }

    @Test
    void minutesAreMergedByPrice_beforeJudgingTheExtreme() {
        // 99.00 trades 3 + 3 over two minutes: 6 contracts against ~10 per price, not thin at 0.19
        List<TradesBar1m> minutes = List.of(
                minute(new double[]{99.00, 1, 2}, new double[]{99.25, 0, 0}, new double[]{99.50, 10, 10}),
                minute(new double[]{99.00, 2, 1}, new double[]{99.50, 2, 2}));
        assertEquals(List.of(), FootprintReasons.of(sweep(SweepDirection.BULLISH), minutes, TICK, RULES));
        assertEquals(List.of(Reason.THIN_EXTREME), FootprintReasons.of(sweep(SweepDirection.BULLISH), minutes, TICK,
                new FootprintRules(1.0)));
    }

    @Test
    void noFootprint_noReason() {
        assertEquals(List.of(), FootprintReasons.of(sweep(SweepDirection.BULLISH), List.of(), TICK, RULES));
    }
}
