package com.algofuture.bots.liquiditysweep.domain.model;

import java.util.Locale;
import java.util.Map;

/**
 * Contract economics needed to size a sweep in ticks and money.
 *
 * {@code tickSize} comes from the {@code instrument} table when the contract is registered there;
 * {@code instrument} has no point value column, so it always comes from the CME specs below.
 *
 * @param root          contract root ("NQ"), the symbol convention of {@code strategy_trade}
 * @param sourceSymbol  symbol as stored in {@code trade_1m_live} ("NQ.FUT")
 */
public record ContractSpec(String root, String sourceSymbol, double tickSize, double pointValue) {

    private static final Map<String, Double> CME_TICK_SIZE = Map.of(
            "NQ", 0.25, "MNQ", 0.25, "ES", 0.25, "MES", 0.25,
            "YM", 1.0, "MYM", 1.0, "RTY", 0.1, "M2K", 0.1);

    private static final Map<String, Double> CME_POINT_VALUE = Map.of(
            "NQ", 20.0, "MNQ", 2.0, "ES", 50.0, "MES", 5.0,
            "YM", 5.0, "MYM", 0.5, "RTY", 50.0, "M2K", 5.0);

    /** "NQ.FUT" / "NQ.c.0" → "NQ". */
    public static String rootOf(String symbol) {
        return symbol.split("\\.")[0].toUpperCase(Locale.ROOT);
    }

    /** Builds the spec, preferring the {@code instrument} tick size when present. */
    public static ContractSpec of(String sourceSymbol, Double instrumentTickSize) {
        String root = rootOf(sourceSymbol);
        Double tick = instrumentTickSize != null ? instrumentTickSize : CME_TICK_SIZE.get(root);
        Double pointValue = CME_POINT_VALUE.get(root);
        if (tick == null || pointValue == null) {
            throw new IllegalArgumentException("Unknown contract specs for " + sourceSymbol
                    + ": register it in the instrument table and in ContractSpec");
        }
        return new ContractSpec(root, sourceSymbol, tick, pointValue);
    }
}
