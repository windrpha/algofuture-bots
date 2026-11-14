package com.algofuture.bots.liquiditysweep.domain.model;

public enum SweepStatus {
    /** Sweep candle printed; waiting for a candle to close beyond the sweep candle's opposite extreme. */
    PENDING,
    /** A later candle closed beyond the sweep candle's high (bullish) / low (bearish). */
    CONFIRMED,
    /** Price took out the sweep candle's extreme before confirming, or the window expired. */
    INVALIDATED
}
