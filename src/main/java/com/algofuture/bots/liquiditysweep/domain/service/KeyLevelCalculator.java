package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel.Type;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds, for each trading day, the key levels still holding liquidity at the session start: overnight
 * session, previous day and previous week extremes, plus (with {@code hourLevels}) the extreme of every
 * complete overnight 1h candle, and (with {@code openingRangeMinutes}) the opening range high/low, which
 * only become levels once the range has closed.
 *
 * Works on 1m candles so window boundaries (18:00 ET, 08:30 CT…) are exact regardless of the
 * interval the sweep is detected on. Days are Globex trade dates: a candle at or after 18:00 ET
 * belongs to the next date (Sunday evening → Monday). Holidays need no calendar: "previous day"
 * and "previous week" are the latest ones that actually have data.
 *
 * A level is dropped if any 1m candle between the end of its window and the session start already
 * traded STRICTLY beyond it (e.g. a PDL taken out overnight is no longer resting liquidity at the open).
 */
public final class KeyLevelCalculator {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime GLOBEX_OPEN = LocalTime.of(18, 0);
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    private final SweepDetectorConfig config;

    public KeyLevelCalculator(SweepDetectorConfig config) {
        this.config = config;
    }

    /** {@code m1} ascending by time; must start early enough to cover the previous week of the first day. */
    public Map<LocalDate, List<KeyLevel>> levelsByDay(List<TradesBar1m> m1, Collection<LocalDate> tradingDays) {
        return compute(m1, tradingDays, true);
    }

    /**
     * Same levels without dropping those already crossed before the session start: what a correlated
     * contract's level "is" for {@link SmtConfirmation}, whether or not it still held liquidity at the open.
     */
    public Map<LocalDate, List<KeyLevel>> rawLevelsByDay(List<TradesBar1m> m1, Collection<LocalDate> tradingDays) {
        return compute(m1, tradingDays, false);
    }

    private Map<LocalDate, List<KeyLevel>> compute(List<TradesBar1m> m1, Collection<LocalDate> tradingDays,
                                                   boolean dropTaken) {
        TreeMap<LocalDate, List<Integer>> byGlobexDate = new TreeMap<>();
        for (int i = 0; i < m1.size(); i++) {
            byGlobexDate.computeIfAbsent(globexDate(m1.get(i).tsEvent()), d -> new ArrayList<>()).add(i);
        }

        Map<LocalDate, List<KeyLevel>> out = new LinkedHashMap<>();
        for (LocalDate day : tradingDays) {
            Instant start = config.sessionStart(day);
            List<KeyLevel> levels = new ArrayList<>();

            // Session: today's Globex candles before the trading window opens
            List<Integer> session = byGlobexDate.getOrDefault(day, List.of()).stream()
                    .filter(i -> m1.get(i).tsEvent().isBefore(start))
                    .toList();
            addExtremes(levels, m1, session, Type.SESSION_HIGH, Type.SESSION_LOW, start);

            // Day: latest Globex date before today with data
            LocalDate prevDay = byGlobexDate.lowerKey(day);
            if (prevDay != null) {
                List<Integer> idx = byGlobexDate.get(prevDay);
                addExtremes(levels, m1, idx, Type.PREV_DAY_HIGH, Type.PREV_DAY_LOW, endOf(m1, idx));
            }

            // Week: every Globex date of the latest week before this one with data
            LocalDate monday = day.with(DayOfWeek.MONDAY);
            LocalDate lastOfPrevWeek = byGlobexDate.lowerKey(monday);
            if (lastOfPrevWeek != null) {
                LocalDate prevMonday = lastOfPrevWeek.with(DayOfWeek.MONDAY);
                List<Integer> idx = byGlobexDate.subMap(prevMonday, true, monday, false).values().stream()
                        .flatMap(List::stream)
                        .toList();
                addExtremes(levels, m1, idx, Type.PREV_WEEK_HIGH, Type.PREV_WEEK_LOW, endOf(m1, idx));
            }

            if (config.hourLevels()) {
                levels.addAll(hourExtremes(m1, session, start));
            }

            if (dropTaken) levels.removeIf(level -> takenBefore(m1, level, start));
            // added after the drop: it forms inside the window, nothing before the open can have taken it
            if (config.openingRangeMinutes() > 0) {
                Instant rangeEnd = config.openingRangeEnd(day);
                List<Integer> range = byGlobexDate.getOrDefault(day, List.of()).stream()
                        .filter(i -> !m1.get(i).tsEvent().isBefore(start) && m1.get(i).tsEvent().isBefore(rangeEnd))
                        .toList();
                addExtremes(levels, m1, range, Type.OPENING_RANGE_HIGH, Type.OPENING_RANGE_LOW, rangeEnd);
            }
            out.put(day, config.hourLevels() ? dedupe(levels) : levels);   // 2.0 keeps its original labels
        }
        return out;
    }

    /** High and low of every complete UTC hour of the overnight session, active from that hour's end. */
    private static List<KeyLevel> hourExtremes(List<TradesBar1m> m1, List<Integer> session, Instant start) {
        Map<Long, List<Integer>> byHour = new LinkedHashMap<>();
        for (int i : session) {
            byHour.computeIfAbsent(m1.get(i).tsEvent().getEpochSecond() / 3600, h -> new ArrayList<>()).add(i);
        }
        List<KeyLevel> out = new ArrayList<>();
        byHour.forEach((hour, idx) -> {
            Instant hourEnd = Instant.ofEpochSecond((hour + 1) * 3600);
            if (hourEnd.isAfter(start)) return;    // the hour still forming at the open is not a level yet
            addExtremes(out, m1, idx, Type.HOUR_HIGH, Type.HOUR_LOW, hourEnd);
        });
        return out;
    }

    /** One level per side and price (an ONL is also some hour's low); the session/day/week one wins. */
    private static List<KeyLevel> dedupe(List<KeyLevel> levels) {
        List<KeyLevel> out = new ArrayList<>();
        for (KeyLevel l : levels) {
            if (out.stream().noneMatch(o -> o.kind() == l.kind() && o.price() == l.price())) out.add(l);
        }
        return out;
    }

    private static void addExtremes(List<KeyLevel> levels, List<TradesBar1m> m1, List<Integer> idx,
                                    Type highType, Type lowType, Instant activeFrom) {
        if (idx.isEmpty()) return;
        TradesBar1m hi = m1.get(idx.get(0)), lo = hi;
        for (int i : idx) {
            TradesBar1m b = m1.get(i);
            if (b.high() >= hi.high()) hi = b;   // equal extremes: the most recent one is the level
            if (b.low() <= lo.low()) lo = b;
        }
        levels.add(new KeyLevel(highType, hi.high(), hi.tsEvent(), activeFrom));
        levels.add(new KeyLevel(lowType, lo.low(), lo.tsEvent(), activeFrom));
    }

    private static boolean takenBefore(List<TradesBar1m> m1, KeyLevel level, Instant until) {
        for (TradesBar1m b : m1) {
            if (b.tsEvent().isBefore(level.activeFrom())) continue;
            if (!b.tsEvent().isBefore(until)) break;
            if (level.kind() == KeyLevel.Kind.HIGH ? b.high() > level.price() : b.low() < level.price()) {
                return true;
            }
        }
        return false;
    }

    private static Instant endOf(List<TradesBar1m> m1, List<Integer> idx) {
        return m1.get(idx.get(idx.size() - 1)).tsEvent().plus(ONE_MINUTE);
    }

    static LocalDate globexDate(Instant instant) {
        ZonedDateTime et = instant.atZone(NEW_YORK);
        return et.toLocalTime().isBefore(GLOBEX_OPEN) ? et.toLocalDate() : et.toLocalDate().plusDays(1);
    }
}
