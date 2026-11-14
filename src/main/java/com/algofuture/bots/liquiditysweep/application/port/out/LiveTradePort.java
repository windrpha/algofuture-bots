package com.algofuture.bots.liquiditysweep.application.port.out;

import com.algofuture.shared.journal.domain.model.LiveTradeRecord;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Where live signals are recorded per funded account. It is also the alert state: after a restart the service reads
 * back what it already sent. Every write reports whether THIS call made the change, so only one caller (even across
 * two running instances) sends each message.
 */
public interface LiveTradePort {

    List<LiveTradeRecord> findDay(LocalDate day);

    /** False when the account already has a signal for that day. */
    boolean insert(LiveTradeRecord trade);

    /** False when the row was already closed. */
    boolean close(long id, String exitType, Instant exitTime, double exitPrice, double pnlUsd);

    /** How many of {@code ids} this call flagged (0 = someone already sent the reminder). */
    int markReminderSent(List<Long> ids);
}
