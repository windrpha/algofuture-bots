package com.algofuture.shared.infrastructure.persistence;

import com.algofuture.shared.marketdata.domain.model.FootprintLevel;
import com.algofuture.shared.marketdata.domain.model.SessionHighLow;
import com.algofuture.shared.marketdata.domain.model.SizeCount;
import com.algofuture.shared.marketdata.domain.model.TimeframeBucket;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;
import com.algofuture.shared.marketdata.domain.model.UnsweptLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.runtime.TransactionConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Market-data repository SHARED by every bot. The single gateway to the database:
 * the native SQL queries over {@code trade_1m_live} live here, once. Each bot consumes
 * them through its own outbound port via a thin adapter.
 */
@ApplicationScoped
public class MarketDataRepository {

    /**
     * Session boundaries are expressed in New York exchange time and converted to UTC via the
     * IANA zone, so they track the EDT↔EST transition automatically (e.g. the 09:30 ET open is
     * 13:30 UTC under EDT and 14:30 UTC under EST). Hardcoding UTC hours would drift by one hour
     * across the daylight-saving change.
     */
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("America/New_York");

    /** NY regular session open: 09:30 ET. */
    private static final LocalTime NY_OPEN = LocalTime.of(9, 30);

    /** Globex (CME) open: 18:00 ET. */
    private static final LocalTime GLOBEX_OPEN = LocalTime.of(18, 0);

    /** Globex (CME) close: 17:00 ET. */
    private static final LocalTime GLOBEX_CLOSE = LocalTime.of(17, 0);

    private static final TypeReference<Map<Double, FootprintLevel>> FOOTPRINT_TYPE =
            new TypeReference<>() {
            };

    @Inject
    EntityManager entityManager;

    @Inject
    ObjectMapper objectMapper;

    /** Candles aggregated to the requested interval, optionally with a merged footprint. */
    @Transactional
    @TransactionConfiguration(timeout = 600) // long-range footprint scans (backtests) outrun the 60s default
    @SuppressWarnings("unchecked")
    public List<TradesBar1m> findTradesBar1mBySymbolAndRange(
            String symbol, OffsetDateTime from, OffsetDateTime to, String interval, boolean includeFootprint) {

        String pgInterval = toPostgresInterval(interval);

        // The footprint JSONB (avg ~1KB/minute) is the heaviest part of this query: it is
        // string_agg'd, shipped, and Jackson-parsed per bar. Callers that only need OHLCV
        // skip it entirely, letting Postgres serve numeric columns from a covering index.
        String footprintSelect = includeFootprint
                ? ",\n    string_agg(footprint::text, chr(10) ORDER BY ts_event)        AS footprint"
                : "";

        // Multi-month aggregations sort many rows; the pooled default work_mem (2MB) spills
        // the sort to disk. SET LOCAL is scoped to this transaction, so it is pooler-safe.
        entityManager.createNativeQuery("SET LOCAL work_mem = '128MB'").executeUpdate();

        List<Object[]> rows = entityManager
                .createNativeQuery("""
                        SELECT
                            date_bin('%s'::interval, ts_event, TIMESTAMPTZ '2000-01-01') AS ts_event,
                            :symbol                                                       AS symbol,
                            (array_agg(open  ORDER BY ts_event))[1]                      AS open,
                            MAX(high)                                                     AS high,
                            MIN(low)                                                      AS low,
                            (array_agg(close ORDER BY ts_event DESC))[1]                 AS close,
                            SUM(volume)                                                   AS volume,
                            SUM(buy_volume)                                               AS buy_volume,
                            SUM(sell_volume)                                              AS sell_volume,
                            SUM(delta)                                                    AS delta,
                            MAX(delta_acc)                                                AS delta_acc,
                            SUM(trade_count)                                              AS trade_count,
                            SUM(volume)::numeric
                                / NULLIF(SUM(trade_count), 0)                             AS avg_trade_size%s
                        FROM trade_1m_live
                        WHERE symbol   = :symbol
                          AND ts_event >= :from
                          AND ts_event <  :to
                        GROUP BY 1
                        ORDER BY 1 ASC
                        """.formatted(pgInterval, footprintSelect))
                .setParameter("symbol", symbol)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        return rows.stream()
                .map(this::rowToTradesBar1m)
                .toList();
    }

    public List<SessionHighLow> findPreNySessionHighLowBySymbols(List<String> symbols, OffsetDateTime refDate) {
        OffsetDateTime[] w = preNyWindow(refDate);
        return findSessionHighLowBySymbols(symbols, w[0], w[1]);
    }

    public List<SessionHighLow> findPreviousDayHighLowBySymbols(List<String> symbols, OffsetDateTime refDate) {
        OffsetDateTime[] w = previousDayWindow(refDate);
        return findSessionHighLowBySymbols(symbols, w[0], w[1]);
    }

    @SuppressWarnings("unchecked")
    public List<UnsweptLevel> findUnsweptHighLowsBySymbol(
            String symbol, OffsetDateTime from, OffsetDateTime to, UnsweptLevel.Timeframe timeframe) {

        List<Object[]> rows = entityManager
                .createNativeQuery("""
                        WITH candles AS (
                            SELECT
                                date_bin('%s'::interval, ts_event,
                                         TIMESTAMPTZ '%s')          AS bucket,
                                MAX(high)                            AS high,
                                MIN(low)                             AS low
                            FROM trade_1m_live
                            WHERE symbol    = :symbol
                              AND ts_event >= :from
                              -- The forming candle IS included (its price sweeps prior
                              -- levels). Its own extremes are flagged is_last and the
                              -- service filters them out via fromLastCandle. `to` must be
                              -- barMinute (exclusive) to leave out the analyzed bar.
                              AND ts_event <  :to
                            GROUP BY 1
                        ),
                        marked AS (
                            SELECT
                                bucket, high, low,
                                MAX(high) OVER w                     AS max_after,
                                MIN(low)  OVER w                     AS min_after,
                                bucket = MAX(bucket) OVER ()         AS is_last
                            FROM candles
                            WINDOW w AS (ORDER BY bucket
                                         ROWS BETWEEN 1 FOLLOWING
                                              AND UNBOUNDED FOLLOWING)
                        )
                        SELECT bucket, 'HIGH' AS kind, high AS price, is_last
                        FROM marked
                        WHERE max_after IS NULL OR high > max_after

                        UNION ALL

                        SELECT bucket, 'LOW'  AS kind, low  AS price, is_last
                        FROM marked
                        WHERE min_after IS NULL OR low < min_after

                        ORDER BY kind, price DESC
                        """.formatted(timeframe.pgInterval, timeframe.binOrigin))
                .setParameter("symbol", symbol)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        return rows.stream()
                .map(row -> rowToUnsweptLevel(row, symbol, timeframe))
                .toList();
    }

    /**
     * Tick size from the {@code instrument} catalog. {@code instrument} and {@code trade_1m_live}
     * name contracts differently ("NQ.c.0" vs "NQ.FUT"), so they are matched by contract root.
     */
    @SuppressWarnings("unchecked")
    public Optional<Double> findTickSize(String symbol) {
        List<Object> rows = entityManager
                .createNativeQuery("""
                        SELECT tick_size
                        FROM instrument
                        WHERE active
                          AND tick_size IS NOT NULL
                          AND upper(split_part(symbol, '.', 1)) = upper(split_part(:symbol, '.', 1))
                        ORDER BY symbol
                        LIMIT 1
                        """)
                .setParameter("symbol", symbol)
                .getResultList();
        return rows.stream().findFirst().map(v -> ((Number) v).doubleValue());
    }

    @SuppressWarnings("unchecked")
    public List<TimeframeBucket> findTimeframeBuckets(
            String symbol, OffsetDateTime from, OffsetDateTime to, UnsweptLevel.Timeframe timeframe) {

        List<Object[]> rows = entityManager
                .createNativeQuery("""
                        SELECT
                            date_bin('%s'::interval, ts_event, TIMESTAMPTZ '%s') AS bucket,
                            MAX(high) AS high,
                            MIN(low)  AS low
                        FROM trade_1m_live
                        WHERE symbol    = :symbol
                          AND ts_event >= :from
                          AND ts_event <  :to
                        GROUP BY 1
                        ORDER BY 1 ASC
                        """.formatted(timeframe.pgInterval, timeframe.binOrigin))
                .setParameter("symbol", symbol)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        return rows.stream()
                .map(row -> new TimeframeBucket(
                        toOffsetDateTime(row[0]).toInstant().toEpochMilli(),
                        ((Number) row[1]).doubleValue(),
                        ((Number) row[2]).doubleValue()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<SessionHighLow> findSessionHighLowBySymbols(
            List<String> symbols, OffsetDateTime from, OffsetDateTime to) {

        List<Object[]> rows = entityManager
                .createNativeQuery("""
                        WITH filtered AS (
                            SELECT symbol, ts_event, high, low, volume, buy_volume, sell_volume
                            FROM trade_1m_live
                            WHERE symbol   IN (:symbols)
                              AND ts_event >= :from
                              AND ts_event <  :to
                        ),
                        agg AS (
                            SELECT
                                symbol,
                                MAX(high)        AS session_high,
                                MIN(low)         AS session_low,
                                SUM(volume)      AS total_volume,
                                SUM(buy_volume)  AS total_buy_volume,
                                SUM(sell_volume) AS total_sell_volume
                            FROM filtered
                            GROUP BY symbol
                        ),
                        high_at AS (
                            SELECT f.symbol, MIN(f.ts_event) AS high_at
                            FROM filtered f
                            JOIN agg ON agg.symbol = f.symbol
                                     AND f.high = agg.session_high
                            GROUP BY f.symbol
                        ),
                        low_at AS (
                            SELECT f.symbol, MIN(f.ts_event) AS low_at
                            FROM filtered f
                            JOIN agg ON agg.symbol = f.symbol
                                     AND f.low = agg.session_low
                            GROUP BY f.symbol
                        )
                        SELECT
                            agg.symbol,
                            agg.session_high,
                            agg.session_low,
                            ha.high_at,
                            la.low_at,
                            CAST(:from AS TIMESTAMPTZ) AS session_from,
                            CAST(:to   AS TIMESTAMPTZ) AS session_to,
                            agg.total_volume,
                            agg.total_buy_volume,
                            agg.total_sell_volume
                        FROM agg
                        JOIN high_at ha ON ha.symbol = agg.symbol
                        JOIN low_at  la ON la.symbol = agg.symbol
                        ORDER BY agg.symbol
                        """)
                .setParameter("symbols", symbols)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        return rows.stream()
                .map(this::rowToSessionHighLow)
                .toList();
    }

    // ------------------------------------------------------------------
    // Session windows (Globex / pre-NY)
    // ------------------------------------------------------------------

    /**
     * Previous full Globex day: previous-trading-day-minus-1 18:00 ET (Globex open) → previous
     * trading day 17:00 ET (Globex close). Boundaries are NY-zone times, so the UTC instants shift
     * with DST (e.g. 18:00 ET = 22:00 UTC in EDT, 23:00 UTC in EST).
     */
    private OffsetDateTime[] previousDayWindow(OffsetDateTime refDate) {
        LocalDate today = refDate.toLocalDate();
        LocalDate closeDay = previousTradingDay(today);

        OffsetDateTime from = nyTime(closeDay.minusDays(1), GLOBEX_OPEN);
        OffsetDateTime to = nyTime(closeDay, GLOBEX_CLOSE);

        return new OffsetDateTime[]{from, to};
    }

    /**
     * Pre-NY overnight window: yesterday 18:00 ET (Globex open) → today 09:30 ET (NY open).
     * Boundaries are NY-zone times, so the UTC instants shift with DST (e.g. 09:30 ET = 13:30 UTC
     * in EDT, 14:30 UTC in EST).
     */
    private OffsetDateTime[] preNyWindow(OffsetDateTime refDate) {
        LocalDate today = refDate.toLocalDate();

        OffsetDateTime from = nyTime(today.minusDays(1), GLOBEX_OPEN);
        OffsetDateTime to = nyTime(today, NY_OPEN);

        return new OffsetDateTime[]{from, to};
    }

    /** Builds an instant from a New York exchange-local date + time, resolving the UTC offset via DST. */
    private OffsetDateTime nyTime(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(EXCHANGE_ZONE).toOffsetDateTime();
    }

    private LocalDate previousTradingDay(LocalDate date) {
        LocalDate candidate = date.minusDays(1);
        while (candidate.getDayOfWeek() == DayOfWeek.SATURDAY
                || candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    // ------------------------------------------------------------------
    // Row -> market domain model mapping
    // ------------------------------------------------------------------

    private UnsweptLevel rowToUnsweptLevel(Object[] row, String symbol, UnsweptLevel.Timeframe timeframe) {
        OffsetDateTime candleStart = toOffsetDateTime(row[0]);
        UnsweptLevel.Kind kind = UnsweptLevel.Kind.valueOf((String) row[1]);
        double price = ((Number) row[2]).doubleValue();
        boolean fromLastCandle = (Boolean) row[3];

        return new UnsweptLevel(
                symbol.replace(".FUT", ""), timeframe, kind,
                price, candleStart, fromLastCandle);
    }

    private TradesBar1m rowToTradesBar1m(Object[] row) {
        Instant tsEvent = row[0] instanceof Instant i
                ? i
                : ((OffsetDateTime) row[0]).toInstant();
        String symbol = (String) row[1];
        double open = ((Number) row[2]).doubleValue();
        double high = ((Number) row[3]).doubleValue();
        double low = ((Number) row[4]).doubleValue();
        double close = ((Number) row[5]).doubleValue();
        long volume = ((Number) row[6]).longValue();
        long buyVolume = ((Number) row[7]).longValue();
        long sellVolume = ((Number) row[8]).longValue();
        long delta = ((Number) row[9]).longValue();
        long deltaAcc = ((Number) row[10]).longValue();
        int tradeCount = ((Number) row[11]).intValue();
        double avgTradeSize = ((Number) row[12]).doubleValue();
        Map<Double, FootprintLevel> footprint = row.length > 13
                ? parseAndMergeFootprint((String) row[13])
                : Map.of();

        return new TradesBar1m(
                tsEvent, symbol, open, high, low, close,
                volume, buyVolume, sellVolume, delta, deltaAcc,
                tradeCount, avgTradeSize, footprint);
    }

    /**
     * The footprint lives as per-minute JSONB in {@code trade_1m_live}. When a candle
     * spans several minutes (interval > 1m), {@code string_agg} concatenates each
     * minute's JSON separated by a newline, and here they are merged by summing volumes,
     * trades and the per-contract-size distribution at each price.
     */
    private Map<Double, FootprintLevel> parseAndMergeFootprint(String aggregatedJson) {
        if (aggregatedJson == null || aggregatedJson.isBlank()) {
            return Map.of();
        }

        NavigableMap<Double, FootprintLevel> merged = new TreeMap<>();
        for (String json : aggregatedJson.split("\n")) {
            if (json.isBlank()) {
                continue;
            }
            Map<Double, FootprintLevel> minute;
            try {
                minute = objectMapper.readValue(json, FOOTPRINT_TYPE);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to parse footprint JSON", e);
            }
            minute.forEach((price, level) -> merged.merge(price, level, this::mergeLevel));
        }
        return merged;
    }

    private FootprintLevel mergeLevel(FootprintLevel a, FootprintLevel b) {
        NavigableMap<Long, SizeCount> sizes = new TreeMap<>(a.sizeDistribution());
        b.sizeDistribution().forEach((size, sc) ->
                sizes.merge(size, sc, (x, y) -> new SizeCount(
                        x.contractSize(),
                        x.buyCount() + y.buyCount(),
                        x.sellCount() + y.sellCount())));

        return new FootprintLevel(
                a.price(),
                a.buyVolume() + b.buyVolume(),
                a.sellVolume() + b.sellVolume(),
                a.buyTrades() + b.buyTrades(),
                a.sellTrades() + b.sellTrades(),
                sizes);
    }

    private SessionHighLow rowToSessionHighLow(Object[] row) {
        String symbol = ((String) row[0]).replace(".FUT", "");
        double sessionHigh = ((Number) row[1]).doubleValue();
        double sessionLow = ((Number) row[2]).doubleValue();
        OffsetDateTime highAt = toOffsetDateTime(row[3]);
        OffsetDateTime lowAt = toOffsetDateTime(row[4]);
        OffsetDateTime sessionFrom = toOffsetDateTime(row[5]);
        OffsetDateTime sessionTo = toOffsetDateTime(row[6]);

        return new SessionHighLow(symbol, sessionHigh, sessionLow, highAt, lowAt, sessionFrom, sessionTo);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private OffsetDateTime toOffsetDateTime(Object raw) {
        if (raw instanceof OffsetDateTime odt) return odt.withOffsetSameInstant(ZoneOffset.UTC);
        if (raw instanceof LocalDateTime ldt) return ldt.atOffset(ZoneOffset.UTC);
        if (raw instanceof Timestamp ts) return ts.toLocalDateTime().atOffset(ZoneOffset.UTC);
        if (raw instanceof Instant i) return i.atOffset(ZoneOffset.UTC);
        throw new IllegalArgumentException("Cannot convert %s to OffsetDateTime".formatted(raw.getClass()));
    }

    private String toPostgresInterval(String interval) {
        if (interval == null || interval.length() < 2) {
            throw new IllegalArgumentException("Invalid interval: " + interval);
        }
        int amount = Integer.parseInt(interval.substring(0, interval.length() - 1));
        String unit = switch (interval.charAt(interval.length() - 1)) {
            case 'm' -> "minutes";
            case 'h' -> "hours";
            case 'd' -> "days";
            case 'w' -> "weeks";
            default -> throw new IllegalArgumentException("Unsupported unit in interval: " + interval);
        };
        return amount + " " + unit;
    }
}