package com.algofuture.bots.liquiditysweep.domain.model;

import java.time.Instant;

/**
 * A session / day / week extreme where stops rest: the only liquidity the bot trades.
 *
 * @param formedAt  start of the 1m candle that printed the extreme
 * @param activeFrom end of the window the extreme comes from; from here on, any trade beyond the
 *                   price consumes the level
 */
public record KeyLevel(Type type, double price, Instant formedAt, Instant activeFrom) {

    public enum Kind { HIGH, LOW }

    public enum Type {
        /** Overnight session: Globex open (18:00 ET) → trading-window start. */
        SESSION_HIGH("ONH", Kind.HIGH, 1), SESSION_LOW("ONL", Kind.LOW, 1),
        /** Previous Globex day: 18:00 ET → 17:00 ET. */
        PREV_DAY_HIGH("PDH", Kind.HIGH, 2), PREV_DAY_LOW("PDL", Kind.LOW, 2),
        /** Previous Globex week: Sunday 18:00 ET → Friday 17:00 ET. */
        PREV_WEEK_HIGH("PWH", Kind.HIGH, 3), PREV_WEEK_LOW("PWL", Kind.LOW, 3),
        /** A complete 1h candle of the overnight session whose extreme is still untouched at the session start. */
        HOUR_HIGH("H1H", Kind.HIGH, 0), HOUR_LOW("H1L", Kind.LOW, 0),
        /** Opening range: the first minutes of the trading window, tradeable only once the range has closed. */
        OPENING_RANGE_HIGH("ORH", Kind.HIGH, 0), OPENING_RANGE_LOW("ORL", Kind.LOW, 0),
        /** Intraday swing (fractal) extreme printed inside the trading window, tradeable once confirmed (4.0). */
        SWING_HIGH("SWH", Kind.HIGH, 0), SWING_LOW("SWL", Kind.LOW, 0);

        public final String label;
        public final Kind kind;
        /** How much liquidity the level holds: week > day > overnight session > hour / opening range. */
        public final int rank;

        Type(String label, Kind kind, int rank) {
            this.label = label;
            this.kind = kind;
            this.rank = rank;
        }
    }

    public Kind kind() {
        return type.kind;
    }
}
