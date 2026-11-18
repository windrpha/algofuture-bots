package com.algofuture.shared.journal.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One {@code live_trade} row: a live signal as sent to one funded account (or skipped for it), and its outcome.
 * {@code id} is null until inserted; exit fields are null while {@code status} is {@code open}.
 */
public record LiveTradeRecord(Long id, LocalDate tradeDay, String accountId, String accountLabel, String strategy,
                              String symbol, String micro, int contracts, String side, Instant signalTime,
                              double signalPrice, double stopLoss, double takeProfit, double entryEstimate,
                              double riskUsd, double rewardUsd, List<String> reasons, String status, String skipReason,
                              String exitType, Instant exitTime, Double exitPrice, Double pnlUsd, boolean reminderSent) {

    public static final String OPEN = "open", SKIPPED = "skipped", CLOSED = "closed";

    public boolean isOpen() {
        return OPEN.equals(status);
    }

    /** Open, or skipped and the signal's outcome not recorded yet (the trader still hears how it ended). */
    public boolean awaitsOutcome() {
        return isOpen() || (SKIPPED.equals(status) && exitType == null);
    }

    public boolean isLong() {
        return "long".equals(side);
    }
}
