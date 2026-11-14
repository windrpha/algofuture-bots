package com.algofuture.shared.infrastructure.persistence.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Mapping of the {@code trade_1m_live} table (1m candles with a JSONB footprint).
 * <p>
 * SHARED data infrastructure: it exists so Hibernate can bootstrap the persistence
 * unit. The actual queries are native SQL in
 * {@link com.algofuture.shared.infrastructure.persistence.MarketDataRepository}.
 */
@Entity
@Table(name = "trade_1m_live")
@IdClass(Trade1mLiveId.class)
public class Trade1mLive extends PanacheEntityBase {

    @Id
    @Column(name = "ts_event", nullable = false)
    public OffsetDateTime tsEvent;

    @Id
    @Column(name = "symbol", nullable = false)
    public String symbol;

    @Column(name = "open", nullable = false)
    public double open;

    @Column(name = "high", nullable = false)
    public double high;

    @Column(name = "low", nullable = false)
    public double low;

    @Column(name = "close", nullable = false)
    public double close;

    @Column(name = "volume", nullable = false)
    public long volume;

    @Column(name = "buy_volume", nullable = false)
    public long buyVolume;

    @Column(name = "sell_volume", nullable = false)
    public long sellVolume;

    @Column(name = "delta", nullable = false)
    public long delta;

    @Column(name = "delta_acc", nullable = false)
    public long deltaAcc;

    @Column(name = "trade_count", nullable = false)
    public int tradeCount;

    @Column(name = "avg_trade_size", nullable = false, precision = 10, scale = 2)
    public BigDecimal avgTradeSize;

    @Column(name = "created_at", insertable = false, updatable = false)
    public OffsetDateTime createdAt;
}
