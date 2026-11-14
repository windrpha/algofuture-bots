package com.algofuture.bots.liquiditysweep.domain.model;

/** Values match {@code strategy_trade.status}. */
public enum TradeResult {
    OPEN, WIN, LOSS, BREAKEVEN;

    public String dbValue() {
        return name().toLowerCase();
    }
}
