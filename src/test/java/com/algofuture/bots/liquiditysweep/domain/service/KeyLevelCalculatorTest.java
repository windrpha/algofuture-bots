package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel.Type;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class KeyLevelCalculatorTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 9);
    private static final LocalDate TUE = LocalDate.of(2026, 11, 10);
    private final KeyLevelCalculator calculator = new KeyLevelCalculator(SweepDetectorConfig.DEFAULT);

    private static TradesBar1m m1(String utc, double high, double low) {
        return new TradesBar1m(Instant.parse(utc), "NQ.FUT", low, high, low, high, 0, 0, 0, 0, 0, 0, 0, Map.of());
    }

    /** Sparse 1m history: Thu–Fri of the previous week, Monday, and Tuesday up to its open. */
    private static List<TradesBar1m> history(TradesBar1m... extra) {
        List<TradesBar1m> bars = new ArrayList<>(List.of(
                m1("2026-11-05T15:00:00Z", 200, 150),   // Thu
                m1("2026-11-06T15:00:00Z", 210, 160),   // Fri
                m1("2026-11-08T23:30:00Z", 190, 170),   // Sun 18:30 ET → Globex Monday
                m1("2026-11-09T15:00:00Z", 195, 165),   // Mon
                m1("2026-11-09T23:30:00Z", 185, 175),   // Mon 18:30 ET → Globex Tuesday
                m1("2026-11-10T13:00:00Z", 186, 172),   // Tue 07:00 CT, overnight
                m1("2026-11-10T14:30:00Z", 180, 176))); // Tue 08:30 CT, inside the window
        bars.addAll(Arrays.asList(extra));
        bars.sort((a, b) -> a.tsEvent().compareTo(b.tsEvent()));
        return bars;
    }

    private static Map<Type, Double> byType(List<KeyLevel> levels) {
        return levels.stream().collect(Collectors.toMap(KeyLevel::type, KeyLevel::price));
    }

    @Test
    void sessionDayAndWeekLevels() {
        List<KeyLevel> levels = calculator.levelsByDay(history(), List.of(TUE)).get(TUE);

        assertEquals(Map.of(
                Type.SESSION_HIGH, 186.0, Type.SESSION_LOW, 172.0,       // Mon 18:00 ET → Tue 08:30 CT
                Type.PREV_DAY_HIGH, 195.0, Type.PREV_DAY_LOW, 165.0,     // Globex Monday (incl. Sunday night)
                Type.PREV_WEEK_HIGH, 210.0, Type.PREV_WEEK_LOW, 150.0),  // Thu + Fri
                byType(levels));
        KeyLevel onl = levels.stream().filter(l -> l.type() == Type.SESSION_LOW).findFirst().orElseThrow();
        assertEquals(Instant.parse("2026-11-10T13:00:00Z"), onl.formedAt());
        assertEquals(Instant.parse("2026-11-10T14:30:00Z"), onl.activeFrom());
    }

    @Test
    void monday_previousDayIsFriday() {
        Map<Type, Double> levels = byType(calculator.levelsByDay(history(), List.of(MON)).get(MON));

        assertEquals(210.0, levels.get(Type.PREV_DAY_HIGH));
        assertEquals(160.0, levels.get(Type.PREV_DAY_LOW));
        assertEquals(150.0, levels.get(Type.PREV_WEEK_LOW));
        assertEquals(170.0, levels.get(Type.SESSION_LOW));        // Sunday evening session
    }

    @Test
    void levelTakenOvernight_isDropped() {
        // the overnight trades under Monday's low (165) before the open: PDL is no longer resting liquidity
        List<KeyLevel> levels = calculator.levelsByDay(
                history(m1("2026-11-10T10:00:00Z", 180, 164)), List.of(TUE)).get(TUE);

        assertFalse(byType(levels).containsKey(Type.PREV_DAY_LOW));
        assertEquals(164.0, byType(levels).get(Type.SESSION_LOW));
        assertEquals(150.0, byType(levels).get(Type.PREV_WEEK_LOW));
    }

    @Test
    void hourLevels_addUntouchedOvernightHourExtremes() {
        SweepDetectorConfig d = SweepDetectorConfig.DEFAULT;
        SweepDetectorConfig v21 = new SweepDetectorConfig(d.minPenetrationTicks(), d.minExcursionBars(), d.reclaimBars(),
                d.confirmationBars(), d.rewardRisk(), d.maxHoldBars(), d.sessionStart(), d.sessionEnd(),
                d.lastSweepTime(), d.zone(), d.flatAtSessionClose(), true);
        List<KeyLevel> levels = new KeyLevelCalculator(v21).levelsByDay(history(
                m1("2026-11-10T08:10:00Z", 181, 168),       // 02:10 CT: the overnight low
                m1("2026-11-10T10:19:00Z", 181, 171),       // 04:19 CT hour low 171...
                m1("2026-11-10T11:30:00Z", 183, 170)),      // ...taken by the 05:30 CT low 170
                List.of(TUE)).get(TUE);

        List<Double> hourLows = levels.stream().filter(l -> l.type() == Type.HOUR_LOW).map(KeyLevel::price).sorted().toList();
        // 175 and 171 were taken before the open; 168 is the ONL, so it is not repeated as an H1L
        assertEquals(List.of(170.0, 172.0), hourLows);
        assertEquals(Instant.parse("2026-11-10T12:00:00Z"), levels.stream()
                .filter(l -> l.type() == Type.HOUR_LOW && l.price() == 170).findFirst().orElseThrow().activeFrom());
        assertEquals(168.0, levels.stream().filter(l -> l.type() == Type.SESSION_LOW).findFirst().orElseThrow().price());
        assertEquals("liquidity sweep 2.1", v21.strategyName());
        assertEquals("liquidity sweep 2.0", SweepDetectorConfig.DEFAULT.strategyName());
    }

    @Test
    void openingRange_firstMinutesOfTheWindow_activeOnceClosed() {
        SweepDetectorConfig d = SweepDetectorConfig.DEFAULT;
        SweepDetectorConfig v31 = new SweepDetectorConfig(d.minPenetrationTicks(), d.minExcursionBars(), d.reclaimBars(),
                d.confirmationBars(), d.rewardRisk(), d.maxHoldBars(), d.sessionStart(), d.sessionEnd(),
                d.lastSweepTime(), d.zone(), d.flatAtSessionClose(), false, 30);
        List<KeyLevel> levels = new KeyLevelCalculator(v31).levelsByDay(history(
                m1("2026-11-10T14:59:00Z", 182, 177),       // 08:59 CT: last candle of the range
                m1("2026-11-10T15:00:00Z", 190, 160)),      // 09:00 CT: after the range, ignored
                List.of(TUE)).get(TUE);

        Map<Type, Double> byType = byType(levels);
        assertEquals(182.0, byType.get(Type.OPENING_RANGE_HIGH));   // 08:30 high 180, 08:59 high 182
        assertEquals(176.0, byType.get(Type.OPENING_RANGE_LOW));    // 08:30 low 176
        assertEquals(Instant.parse("2026-11-10T15:00:00Z"), levels.stream()
                .filter(l -> l.type() == Type.OPENING_RANGE_HIGH).findFirst().orElseThrow().activeFrom());
        assertFalse(byType(calculator.levelsByDay(history(), List.of(TUE)).get(TUE)).containsKey(Type.OPENING_RANGE_LOW));
    }

    @Test
    void globexDate_eveningBelongsToNextDay() {
        assertEquals(MON, KeyLevelCalculator.globexDate(Instant.parse("2026-11-08T23:00:00Z")));  // Sun 18:00 ET
        assertEquals(MON, KeyLevelCalculator.globexDate(Instant.parse("2026-11-09T21:59:00Z")));  // Mon 16:59 ET
    }
}
