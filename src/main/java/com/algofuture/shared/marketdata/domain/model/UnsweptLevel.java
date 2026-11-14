package com.algofuture.shared.marketdata.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

/**
 * An extreme (high or low) of an aggregated candle (1h / 4h / 1d / 1w) that no later
 * price has taken out: liquidity still resting.
 *
 * A HIGH is "swept" when some later candle prints a STRICTLY higher high (an equal
 * touch does not trigger the stops resting above). Mirror case for LOWs. With equal
 * highs, only the most recent of the group survives.
 *
 * {@code cluster} is optional: when several unswept levels fall very close together
 * they are grouped and this field describes the cluster. It is {@code null} (and
 * omitted from JSON) for an isolated level.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UnsweptLevel(
        String symbol,
        Timeframe timeframe,
        Kind kind,
        double price,
        OffsetDateTime candleStart,
        boolean fromLastCandle,
        Cluster cluster
) {

    /** Isolated level (no cluster). */
    public UnsweptLevel(String symbol, Timeframe timeframe, Kind kind,
                        double price, OffsetDateTime candleStart, boolean fromLastCandle) {
        this(symbol, timeframe, kind, price, candleStart, fromLastCandle, null);
    }

    /** Cluster of unswept levels very close to one another. */
    public record Cluster(int count, double priceMin, double priceMax) {}

    public enum Kind { HIGH, LOW }

    public enum Timeframe {
        H1("1 hour",  "2000-01-01 23:00:00+00",   24),
        H4("4 hours", "2000-01-01 23:00:00+00",  30),
        D1("1 day",   "2000-01-01 23:00:00+00",  30),
        W1("7 days",  "2000-01-02 23:00:00+00", 180);

        public final String pgInterval;
        /** date_bin origin aligned to the Globex open (23:00 UTC);
         *  for W1, a Sunday 23:00 UTC (weekly Globex open). */
        public final String binOrigin;
        /** Lookback window (days) used to detect unswept levels in this timeframe. */
        public final int lookbackDays;

        Timeframe(String pgInterval, String binOrigin, int lookbackDays) {
            this.pgInterval = pgInterval;
            this.binOrigin = binOrigin;
            this.lookbackDays = lookbackDays;
        }
    }
}
