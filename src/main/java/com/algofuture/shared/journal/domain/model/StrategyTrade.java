package com.algofuture.shared.journal.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One row of the {@code strategy_trade} journal, shared by every bot that records trades.
 * Exit / result fields are {@code null} while the trade is open.
 */
public record StrategyTrade(
        String symbol,
        int strategyId,
        String side,                 // long | short
        Instant entryTime,
        double entryPrice,
        String entryReason,
        Instant exitTime,
        Double exitPrice,
        String exitReason,
        Double stopLoss,
        Double takeProfit,
        BigDecimal quantity,
        BigDecimal rrPlanned,
        BigDecimal rrRealized,
        String status,               // open | win | loss | breakeven
        Double pnl,
        Integer pnlTicks,
        Map<String, Object> metadata,
        List<String> tags,
        String author                // created_by / updated_by
) {
    public enum UpsertOutcome { INSERTED, UPDATED, UNCHANGED }
}
