package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel.Type;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDirection;
import com.algofuture.bots.liquiditysweep.domain.model.SweepStatus;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiquiditySweepDetectorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    /** 08:30 Chicago (CST) on DAY. */
    private static final Instant OPEN = Instant.parse("2026-11-10T14:30:00Z");
    private static final Duration BAR = Duration.ofMinutes(5);
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    /** Accepts any sweep (one-candle wicks too, all session long), so each rule is tested in isolation. */
    private static final SweepDetectorConfig ANY = new SweepDetectorConfig(1, 1, 3, 3, 2.0, 0,
            LocalTime.of(8, 30), LocalTime.of(15, 0), null, CHICAGO, false, false);
    private final LiquiditySweepDetector detector = new LiquiditySweepDetector(ANY, 0.25);

    /** {open, high, low, close} per 5m candle, the first one starting at {@code first}. */
    private static List<TradesBar1m> bars(Instant first, double[]... ohlc) {
        List<TradesBar1m> out = new ArrayList<>();
        for (int i = 0; i < ohlc.length; i++) {
            double[] c = ohlc[i];
            out.add(new TradesBar1m(first.plus(BAR.multipliedBy(i)), "NQ.FUT", c[0], c[1], c[2], c[3],
                    0, 0, 0, 0, 0, 0, 0, Map.of()));
        }
        return out;
    }

    private static Map<LocalDate, List<KeyLevel>> levels(KeyLevel... levels) {
        return Map.of(DAY, List.of(levels));
    }

    private static KeyLevel level(Type type, double price) {
        return new KeyLevel(type, price, OPEN.minus(Duration.ofHours(3)), OPEN);
    }

    private List<LiquiditySweep> confirmed(List<TradesBar1m> bars, Map<LocalDate, List<KeyLevel>> levels) {
        return detector.detect("NQ.FUT", bars, levels).stream()
                .filter(s -> s.status() == SweepStatus.CONFIRMED).toList();
    }

    /**
     * The screenshot: the big red candle closes the overnight session leaving its low (yellow line,
     * ONL = 140); at the 08:30 open the green candle wicks below it and closes back above, and the
     * next green candle closes above the sweep candle's high.
     */
    @Test
    void screenshotPattern_sessionLowSweptAtOpen() {
        List<TradesBar1m> series = bars(OPEN.minus(BAR.multipliedBy(4)),
                new double[]{832, 832, 705, 705},     // 08:10 red
                new double[]{732, 826, 635, 760},     // 08:15 small green
                new double[]{792, 1128, 497, 1064},   // 08:20 green impulse
                new double[]{1103, 1108, 140, 233},   // 08:25 big red → overnight low 140
                new double[]{173, 445, 85, 280},      // 08:30 sweep: wick to 85, closes 280 > 140
                new double[]{203, 770, 180, 703},     // 08:35 confirmation: closes 703 > 445
                new double[]{720, 765, 505, 629});
        KeyLevel onl = new KeyLevel(Type.SESSION_LOW, 140, series.get(3).tsEvent(), OPEN);

        List<LiquiditySweep> result = confirmed(series, levels(onl));

        assertEquals(1, result.size());
        LiquiditySweep s = result.get(0);
        assertEquals(SweepDirection.BULLISH, s.direction());
        assertEquals(140, s.sweptLevel());
        assertEquals(Type.SESSION_LOW, s.levelType());
        assertEquals(series.get(3).tsEvent(), s.levelAt());
        assertEquals(OPEN, s.sweepAt());
        assertEquals(series.get(5).tsEvent(), s.confirmedAt());
        assertEquals(55, s.penetration());
        assertEquals(703, s.entry());
        assertEquals(85, s.stop());
        assertEquals(703 + 2 * (703 - 85), s.target());
    }

    @Test
    void sweepBeforeSessionStart_isIgnored() {
        List<TradesBar1m> series = bars(OPEN.minus(BAR.multipliedBy(3)),
                new double[]{105, 106, 98, 102},       // 08:15 sweep of PDL, but before 08:30
                new double[]{102, 108, 101, 107},
                new double[]{107, 109, 104, 108});

        assertTrue(detector.detect("NQ.FUT", series, levels(level(Type.PREV_DAY_LOW, 100))).isEmpty());
    }

    @Test
    void levelStillForming_cannotBeSwept() {
        // an opening range low active from 08:40: the 08:30 wick under it does not count, the 08:40 one does
        KeyLevel orl = new KeyLevel(Type.OPENING_RANGE_LOW, 100, OPEN, OPEN.plus(BAR.multipliedBy(2)));
        List<TradesBar1m> series = bars(OPEN,
                new double[]{105, 106, 98, 102},       // 08:30 below 100, range not closed yet
                new double[]{102, 104, 101, 103},
                new double[]{103, 104, 97, 101},       // 08:40 sweep
                new double[]{101, 108, 100.5, 107});   // 08:45 confirmation

        List<LiquiditySweep> result = confirmed(series, levels(orl));

        assertEquals(1, result.size());
        assertEquals(OPEN.plus(BAR.multipliedBy(2)), result.get(0).sweepAt());
        assertEquals(97, result.get(0).stop());
    }

    @Test
    void ordinaryCandleLow_isNotLiquidity() {
        // candle 1 wicks under candle 0's low and reclaims it, but no key level is there
        List<TradesBar1m> series = bars(OPEN,
                new double[]{110, 112, 100, 105},
                new double[]{105, 106, 98, 102},
                new double[]{102, 108, 101, 107});

        assertTrue(detector.detect("NQ.FUT", series, levels(level(Type.PREV_DAY_LOW, 90))).isEmpty());
    }

    @Test
    void reclaimWithinReclaimBars_isSweepWithExcursionStop() {
        List<TradesBar1m> series = bars(OPEN,
                new double[]{105, 106, 99, 99.5},      // pierces PDL 100, closes below
                new double[]{99.5, 100, 97, 98.5},     // excursion low 97
                new double[]{98.5, 103, 98, 102},      // 3rd candle closes back above 100: sweep
                new double[]{102, 104, 101, 103.5});   // closes above the reclaim candle high (103)

        List<LiquiditySweep> result = confirmed(series, levels(level(Type.PREV_DAY_LOW, 100)));

        assertEquals(1, result.size());
        LiquiditySweep s = result.get(0);
        assertEquals(OPEN, s.piercedAt());
        assertEquals(OPEN.plus(BAR.multipliedBy(2)), s.sweepAt());
        assertEquals(97, s.sweepExtreme());
        assertEquals(103.5, s.entry());
        assertEquals(97, s.stop());
    }

    @Test
    void noReclaimWithinReclaimBars_isBreakdown() {
        List<TradesBar1m> series = bars(OPEN,
                new double[]{105, 106, 98, 99},        // pierce
                new double[]{99, 99.5, 97, 98},
                new double[]{98, 99.5, 96, 97},        // 3 candles closed below: breakdown
                new double[]{97, 103, 96.5, 102},      // too late, the level is gone
                new double[]{102, 108, 101, 107});

        assertTrue(detector.detect("NQ.FUT", series, levels(level(Type.PREV_DAY_LOW, 100))).isEmpty());
    }

    @Test
    void strictMode_pierceCandleMustCloseBack() {
        SweepDetectorConfig strict = new SweepDetectorConfig(1, 1, 1, 3, 2.0, 0,
                LocalTime.of(8, 30), LocalTime.of(15, 0), null, CHICAGO, false, false);
        List<TradesBar1m> series = bars(OPEN,
                new double[]{105, 106, 98, 99},        // closes below PDL 100: breakdown in strict mode
                new double[]{99, 103, 97, 102});

        assertTrue(new LiquiditySweepDetector(strict, 0.25)
                .detect("NQ.FUT", series, levels(level(Type.PREV_DAY_LOW, 100))).isEmpty());
    }

    @Test
    void defaultConfig_ignoresOneCandleWick() {
        List<TradesBar1m> series = bars(OPEN,
                new double[]{105, 106, 98, 102},       // wick under PDL 100, closes back on the same candle
                new double[]{102, 108, 101, 107});

        assertTrue(new LiquiditySweepDetector(SweepDetectorConfig.DEFAULT, 0.25)
                .detect("NQ.FUT", series, levels(level(Type.PREV_DAY_LOW, 100))).isEmpty());
    }

    @Test
    void defaultConfig_takesFailedBreakdown() {
        List<TradesBar1m> series = bars(OPEN,
                new double[]{105, 106, 98, 99},        // closes below PDL 100: breakdown traders get in
                new double[]{99, 103, 97, 102},        // closes back above: they are trapped
                new double[]{102, 108, 101, 107});

        List<LiquiditySweep> result = new LiquiditySweepDetector(SweepDetectorConfig.DEFAULT, 0.25)
                .detect("NQ.FUT", series, levels(level(Type.PREV_DAY_LOW, 100)));
        assertEquals(1, result.size());
        assertEquals(SweepStatus.CONFIRMED, result.get(0).status());
        assertEquals(97, result.get(0).stop());
    }

    @Test
    void defaultConfig_ignoresSweepsFrom11() {
        Instant elevenCt = Instant.parse("2026-11-10T17:00:00Z");
        List<TradesBar1m> series = bars(elevenCt.minus(BAR),
                new double[]{105, 106, 98, 99},        // 10:55 pierce
                new double[]{99, 103, 97, 102},        // 11:00 reclaim: too late
                new double[]{102, 108, 101, 107});

        assertTrue(new LiquiditySweepDetector(SweepDetectorConfig.DEFAULT, 0.25)
                .detect("NQ.FUT", series, levels(level(Type.PREV_DAY_LOW, 100))).isEmpty());
    }

    @Test
    void levelSweptOnce_secondPokeIsNotASweep() {
        List<TradesBar1m> series = bars(OPEN,
                new double[]{105, 106, 98, 102},       // sweeps PDL 100
                new double[]{102, 104, 101, 103},
                new double[]{103, 104, 97, 101});      // under 100 again: already consumed

        List<LiquiditySweep> result = detector.detect("NQ.FUT", series, levels(level(Type.PREV_DAY_LOW, 100)));
        assertEquals(1, result.size());
        assertEquals(OPEN, result.get(0).sweepAt());
    }

    @Test
    void stackedLevels_reportsDeepest() {
        List<TradesBar1m> series = bars(OPEN,
                new double[]{105, 106, 97, 102});      // takes ONL 100, PDL 99 and PWL 98

        LiquiditySweep s = detector.detect("NQ.FUT", series, levels(
                level(Type.SESSION_LOW, 100), level(Type.PREV_DAY_LOW, 99), level(Type.PREV_WEEK_LOW, 98))).get(0);
        assertEquals(98, s.sweptLevel());
        assertEquals(Type.PREV_WEEK_LOW, s.levelType());
        assertEquals(List.of("PWL", "PDL", "ONL"), s.levelsSwept());
    }

    @Test
    void penetrationBelowMinTicks_isIgnored() {
        SweepDetectorConfig strict = new SweepDetectorConfig(4, 1, 3, 3, 2.0, 0,
                LocalTime.of(8, 30), LocalTime.of(15, 0), null, CHICAGO, false, false);
        List<TradesBar1m> series = bars(OPEN,
                new double[]{105, 106, 99.5, 102});    // 2 ticks under, 4 required

        assertTrue(new LiquiditySweepDetector(strict, 0.25)
                .detect("NQ.FUT", series, levels(level(Type.PREV_DAY_LOW, 100))).isEmpty());
    }

    @Test
    void equalTouch_doesNotSweep() {
        List<TradesBar1m> series = bars(OPEN, new double[]{105, 106, 100, 104});

        assertTrue(detector.detect("NQ.FUT", series, levels(level(Type.PREV_DAY_LOW, 100))).isEmpty());
    }

    @Test
    void sweepLowTakenBeforeConfirmation_isInvalidated() {
        List<TradesBar1m> series = bars(OPEN,
                new double[]{105, 106, 98, 102},       // sweep
                new double[]{102, 104, 97, 99});       // breaks 98 before closing above 106

        LiquiditySweep s = detector.detect("NQ.FUT", series, levels(level(Type.PREV_DAY_LOW, 100))).get(0);
        assertEquals(SweepStatus.INVALIDATED, s.status());
        assertNull(s.entry());
    }

    @Test
    void sessionCloseBeforeConfirmation_isInvalidated() {
        Instant lastBar = Instant.parse("2026-11-10T20:55:00Z");   // 14:55 CT
        List<TradesBar1m> series = bars(lastBar,
                new double[]{105, 106, 98, 102},       // sweep on the last candle of the window
                new double[]{102, 108, 101, 107});     // 15:00 CT: outside, cannot confirm

        LiquiditySweep s = detector.detect("NQ.FUT", series, levels(level(Type.PREV_DAY_LOW, 100))).get(0);
        assertEquals(SweepStatus.INVALIDATED, s.status());
    }

    @Test
    void lastCandleSweep_staysPending() {
        List<TradesBar1m> series = bars(OPEN, new double[]{105, 106, 98, 102});

        assertEquals(SweepStatus.PENDING,
                detector.detect("NQ.FUT", series, levels(level(Type.PREV_DAY_LOW, 100))).get(0).status());
    }

    @Test
    void bearishMirror_prevWeekHighSwept() {
        List<TradesBar1m> series = bars(OPEN,
                new double[]{105, 114, 102, 107},      // wicks to 114 over PWH 110, closes 107 < 110
                new double[]{107, 108, 99, 100});      // closes 100 < sweep low 102

        List<LiquiditySweep> result = confirmed(series, levels(level(Type.PREV_WEEK_HIGH, 110)));

        assertEquals(1, result.size());
        LiquiditySweep s = result.get(0);
        assertEquals(SweepDirection.BEARISH, s.direction());
        assertEquals(Type.PREV_WEEK_HIGH, s.levelType());
        assertEquals(110, s.sweptLevel());
        assertEquals(114, s.sweepExtreme());
        assertEquals(100, s.entry());
        assertEquals(114, s.stop());
        assertEquals(100 - 2 * 14, s.target());
    }
}
