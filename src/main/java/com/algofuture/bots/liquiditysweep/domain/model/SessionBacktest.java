package com.algofuture.bots.liquiditysweep.domain.model;

import java.util.List;

/**
 * Result of the one-trade-per-session strategy over a range. {@code summary} counts the sweeps of every
 * contract but only the picked trades; {@code journal} is set only when persisted.
 */
public record SessionBacktest(String runId, List<String> symbols, String interval, SweepDetectorConfig config,
                              SessionConfig session, BacktestSummary summary, List<SessionPick> picks,
                              SweepBacktest.JournalResult journal) {

    public SessionBacktest withJournal(SweepBacktest.JournalResult journal) {
        return new SessionBacktest(runId, symbols, interval, config, session, summary, picks, journal);
    }
}
