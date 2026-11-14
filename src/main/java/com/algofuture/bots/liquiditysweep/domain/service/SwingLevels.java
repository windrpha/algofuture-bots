package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel.Type;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Intraday liquidity (strategy 4.0): a candle of the trading window whose high (low) is the highest (lowest)
 * of the {@code strength} candles on each side is a swing extreme where new stops rest. It only becomes a
 * level once the last of those right-hand candles has closed, so detection never looks ahead.
 */
public final class SwingLevels {

    private SwingLevels() {
    }

    /** {@code levels} plus each day's swing extremes; {@code bars} ascending, same interval as detection. */
    public static Map<LocalDate, List<KeyLevel>> with(Map<LocalDate, List<KeyLevel>> levels, List<TradesBar1m> bars,
                                                      SweepDetectorConfig config, Duration barDuration, int strength) {
        Map<LocalDate, List<TradesBar1m>> byDay = new LinkedHashMap<>();
        for (TradesBar1m b : bars) {
            if (config.inSession(b.tsEvent())) byDay.computeIfAbsent(config.dayOf(b.tsEvent()), d -> new ArrayList<>()).add(b);
        }
        Map<LocalDate, List<KeyLevel>> out = new LinkedHashMap<>();
        levels.forEach((day, dayLevels) -> {
            List<KeyLevel> all = new ArrayList<>(dayLevels);
            List<TradesBar1m> day1 = byDay.getOrDefault(day, List.of());
            for (int i = strength; i < day1.size() - strength; i++) {
                TradesBar1m b = day1.get(i);
                boolean high = true, low = true;
                for (int j = i - strength; j <= i + strength; j++) {
                    high &= day1.get(j).high() <= b.high();
                    low &= day1.get(j).low() >= b.low();
                }
                var activeFrom = day1.get(i + strength).tsEvent().plus(barDuration);
                if (high) all.add(new KeyLevel(Type.SWING_HIGH, b.high(), b.tsEvent(), activeFrom));
                if (low) all.add(new KeyLevel(Type.SWING_LOW, b.low(), b.tsEvent(), activeFrom));
            }
            out.put(day, all);
        });
        return out;
    }
}
