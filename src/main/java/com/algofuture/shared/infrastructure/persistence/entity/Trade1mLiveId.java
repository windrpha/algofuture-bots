package com.algofuture.shared.infrastructure.persistence.entity;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Clave compuesta (ts_event, symbol) de {@link Trade1mLive}. */
public class Trade1mLiveId implements Serializable {

    public OffsetDateTime tsEvent;
    public String symbol;

    public Trade1mLiveId() {
    }

    public Trade1mLiveId(OffsetDateTime tsEvent, String symbol) {
        this.tsEvent = tsEvent;
        this.symbol = symbol;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Trade1mLiveId that)) return false;
        return Objects.equals(tsEvent, that.tsEvent)
                && Objects.equals(symbol, that.symbol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tsEvent, symbol);
    }
}
