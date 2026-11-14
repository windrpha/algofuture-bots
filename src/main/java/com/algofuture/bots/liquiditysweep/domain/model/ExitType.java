package com.algofuture.bots.liquiditysweep.domain.model;

/** Codes match {@code strategy_trade.metadata.exit_type} used by the other strategies. */
public enum ExitType {
    STOP("sl"), TARGET("tp"), TIME("time"), CLOSE("session_close"), OPEN("open");

    public final String code;

    ExitType(String code) {
        this.code = code;
    }
}
