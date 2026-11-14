package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.ContractSpec;
import com.algofuture.bots.liquiditysweep.domain.model.ExitType;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.bots.liquiditysweep.domain.model.SweepTrade;
import com.algofuture.bots.liquiditysweep.domain.model.TradeResult;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SweepTradeSimulatorTest {

    /** 08:30 Chicago (CST): the first candle of the trading window. */
    private static final Instant T0 = Instant.parse("2026-11-10T14:30:00Z");
    private static final Map<LocalDate, List<KeyLevel>> ONL_100 = Map.of(LocalDate.of(2026, 11, 10),
            List.of(new KeyLevel(KeyLevel.Type.SESSION_LOW, 100, T0.minus(Duration.ofHours(2)), T0)));
    private static final Duration BAR = Duration.ofMinutes(5);
    private static final ContractSpec NQ = ContractSpec.of("NQ.FUT", 0.25);
    /** One-candle sweeps, no cutoff, no flat at close: isolates the exit rules under test. */
    private static final SweepDetectorConfig ANY = config(0, false);

    private static SweepDetectorConfig config(int maxHoldBars, boolean flatAtClose) {
        return new SweepDetectorConfig(1, 1, 3, 3, 2.0, maxHoldBars, LocalTime.of(8, 30), LocalTime.of(15, 0),
                null, ZoneId.of("America/Chicago"), flatAtClose, false);
    }

    private static List<TradesBar1m> bars(double[]... ohlc) {
        List<TradesBar1m> out = new ArrayList<>();
        for (int i = 0; i < ohlc.length; i++) {
            double[] c = ohlc[i];
            out.add(new TradesBar1m(T0.plus(BAR.multipliedBy(i)), "NQ.FUT", c[0], c[1], c[2], c[3],
                    0, 0, 0, 0, 0, 0, 0, Map.of()));
        }
        return out;
    }

    private static List<SweepTrade> run(SweepDetectorConfig config, List<TradesBar1m> series) {
        return run(config, series, ONL_100);
    }

    private static List<SweepTrade> run(SweepDetectorConfig config, List<TradesBar1m> series,
                                        Map<LocalDate, List<KeyLevel>> levels) {
        var sweeps = new LiquiditySweepDetector(config, NQ.tickSize()).detect("NQ.FUT", series, levels);
        return new SweepTradeSimulator(config, NQ, BAR).simulate(series, sweeps);
    }

    // ONL 100 → sweep to 98 closing 102 → confirmation closes 107 > 106: entry 107, stop 98, target 125
    private static final double[] LEVEL = {110, 112, 100, 105};
    private static final double[] SWEEP = {105, 106, 98, 102};
    private static final double[] CONFIRM = {102, 108, 101, 107};

    @Test
    void targetHit_isWin() {
        List<SweepTrade> trades = run(ANY, bars(LEVEL, SWEEP, CONFIRM,
                new double[]{107, 115, 104, 114},
                new double[]{114, 126, 113, 124}));

        assertEquals(1, trades.size());
        SweepTrade t = trades.get(0);
        assertEquals(T0.plus(BAR.multipliedBy(3)), t.entryAt());    // close of the confirmation candle
        assertEquals(107, t.entryPrice());
        assertEquals(125, t.takeProfit());
        assertEquals(ExitType.TARGET, t.exitType());
        assertEquals(TradeResult.WIN, t.result());
        assertEquals(T0.plus(BAR.multipliedBy(4)), t.exitAt());
        assertEquals(72, t.pnlTicks());                             // 18 points
        assertEquals(360, t.pnl());                                 // 18 × $20
        assertEquals(2.0, t.rrRealized());
    }

    @Test
    void stopAndTargetInSameCandle_assumesStop() {
        List<SweepTrade> trades = run(ANY, bars(LEVEL, SWEEP, CONFIRM,
                new double[]{107, 130, 90, 110}));

        SweepTrade t = trades.get(0);
        assertEquals(ExitType.STOP, t.exitType());
        assertEquals(TradeResult.LOSS, t.result());
        assertEquals(-180, t.pnl());
        assertEquals(-1.0, t.rrRealized());
    }

    @Test
    void timeExit_atCloseOfLastHeldCandle() {
        SweepDetectorConfig config = config(2, false);
        List<SweepTrade> trades = run(config, bars(LEVEL, SWEEP, CONFIRM,
                new double[]{107, 110, 105, 109},
                new double[]{109, 111, 106, 110}));

        SweepTrade t = trades.get(0);
        assertEquals(ExitType.TIME, t.exitType());
        assertEquals(110, t.exitPrice());
        assertEquals(T0.plus(BAR.multipliedBy(5)), t.exitAt());
        assertEquals(TradeResult.WIN, t.result());
    }

    @Test
    void flatAtSessionClose_exitsAtOpenOfFirstCandleAfter15() {
        Instant beforeClose = Instant.parse("2026-11-10T20:40:00Z");     // 14:40 CT
        List<TradesBar1m> series = new ArrayList<>(bars(LEVEL, SWEEP, CONFIRM));
        series = new ArrayList<>(series.stream().map(b -> new TradesBar1m(
                b.tsEvent().plus(Duration.between(T0, beforeClose)), b.symbol(), b.open(), b.high(), b.low(), b.close(),
                0, 0, 0, 0, 0, 0, 0, Map.of())).toList());
        series.add(new TradesBar1m(Instant.parse("2026-11-10T21:00:00Z"), "NQ.FUT", 110, 111, 109, 110.5,
                0, 0, 0, 0, 0, 0, 0, Map.of()));                         // 15:00 CT
        Map<LocalDate, List<KeyLevel>> levels = Map.of(LocalDate.of(2026, 11, 10),
                List.of(new KeyLevel(KeyLevel.Type.SESSION_LOW, 100, T0, T0)));

        SweepTrade t = run(config(0, true), series, levels).get(0);
        assertEquals(ExitType.CLOSE, t.exitType());
        assertEquals(110, t.exitPrice());
        assertEquals(Instant.parse("2026-11-10T21:00:00Z"), t.exitAt());
    }

    @Test
    void flatAtSessionClose_earlyCloseDay_exitsAtLastCandleOfTheDay() {
        List<TradesBar1m> series = new ArrayList<>(bars(LEVEL, SWEEP, CONFIRM,
                new double[]{107, 110, 105, 109}));                      // 08:45 CT, last candle of a half day
        series.add(new TradesBar1m(Instant.parse("2026-11-11T14:30:00Z"), "NQ.FUT", 130, 131, 129, 130,
                0, 0, 0, 0, 0, 0, 0, Map.of()));                         // next day's open, far beyond the target

        SweepTrade t = run(config(0, true), series).get(0);
        assertEquals(ExitType.CLOSE, t.exitType());
        assertEquals(109, t.exitPrice());
        assertEquals(T0.plus(BAR.multipliedBy(4)), t.exitAt());
    }

    @Test
    void noExitBeforeDataEnds_staysOpen() {
        List<SweepTrade> trades = run(ANY, bars(LEVEL, SWEEP, CONFIRM,
                new double[]{107, 110, 105, 109}));

        SweepTrade t = trades.get(0);
        assertEquals(TradeResult.OPEN, t.result());
        assertNull(t.exitAt());
        assertNull(t.pnl());
    }

    @Test
    void sweepWhileInPosition_isSkipped() {
        // a bearish sweep of PDH 112.5 confirms while the long is still on
        Map<LocalDate, List<KeyLevel>> levels = Map.of(LocalDate.of(2026, 11, 10), List.of(
                ONL_100.values().iterator().next().get(0),
                new KeyLevel(KeyLevel.Type.PREV_DAY_HIGH, 112.5, T0.minus(Duration.ofDays(1)), T0)));
        List<TradesBar1m> series = bars(LEVEL, SWEEP, CONFIRM,
                new double[]{107, 113, 106, 111},      // bearish sweep of PDH
                new double[]{111, 111.5, 105, 105.5},  // bearish confirmation (long still open)
                new double[]{105.5, 126, 105, 125});   // long target

        List<SweepTrade> trades = run(ANY, series, levels);

        assertEquals(1, trades.size());
        assertEquals(TradeResult.WIN, trades.get(0).result());
    }
}
