package com.algofuture.shared.marketdata.domain.model;

/**
 * One aggregated higher-timeframe bar (H1/D1/W1): the bucket's start and its high/low. Used to
 * reconstruct unswept levels in memory during a backtest without re-querying per day.
 */
public record TimeframeBucket(long startEpochMs, double high, double low) {
}
