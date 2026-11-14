package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel.Type;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwingLevelsTest {

    private static final Duration BAR = Duration.ofMinutes(5);
    private static final LocalDate DAY = LocalDate.of(2026, 11, 11);
    private static final Instant OPEN = Instant.parse("2026-11-11T14:30:00Z");   // 08:30 CT

    private static List<TradesBar1m> bars(double[][] hl) {
        List<TradesBar1m> out = new ArrayList<>();
        for (int i = 0; i < hl.length; i++) {
            double h = hl[i][0], l = hl[i][1];
            out.add(new TradesBar1m(OPEN.plus(BAR.multipliedBy(i)), "NQ.FUT", l, h, l, h, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        }
        return out;
    }

    @Test
    void swingHighAndLow_activeOnlyAfterTheRightSideCloses() {
        List<TradesBar1m> bars = bars(new double[][]{
                {102, 100}, {103, 101}, {104, 102}, {110, 103}, {105, 99}, {104, 101}, {103, 102}, {106, 104}});
        Map<LocalDate, List<KeyLevel>> out = SwingLevels.with(Map.of(DAY, List.of()), bars,
                SweepDetectorConfig.DEFAULT, BAR, 3);

        List<KeyLevel> levels = out.get(DAY);
        assertEquals(2, levels.size());   // candles 0-2 and the last 3 lack a full side: never swings
        KeyLevel high = levels.get(0), low = levels.get(1);
        assertEquals(Type.SWING_HIGH, high.type());
        assertEquals(110, high.price());
        assertEquals(OPEN.plus(BAR.multipliedBy(7)), high.activeFrom());   // close of candle 6 = 3 candles later
        assertEquals(Type.SWING_LOW, low.type());
        assertEquals(99, low.price());
        assertEquals(OPEN.plus(BAR.multipliedBy(8)), low.activeFrom());
    }

    @Test
    void keepsExistingLevels() {
        KeyLevel onl = new KeyLevel(Type.SESSION_LOW, 90, OPEN.minusSeconds(3600), OPEN);
        List<KeyLevel> levels = SwingLevels.with(Map.of(DAY, List.of(onl)), bars(new double[][]{{101, 100}}),
                SweepDetectorConfig.DEFAULT, BAR, 3).get(DAY);
        assertTrue(levels.contains(onl));
        assertEquals(1, levels.size());
    }
}
