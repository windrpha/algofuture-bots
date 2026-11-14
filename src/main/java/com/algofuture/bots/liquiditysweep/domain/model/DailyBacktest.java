package com.algofuture.bots.liquiditysweep.domain.model;

import java.util.List;

/**
 * Result of the one-trade-per-day conviction strategy (4.0) over a range. {@code summary} counts the sweeps of
 * every contract but only the picked trades; {@code journal} is set only when persisted.
 */
public record DailyBacktest(String runId, List<String> symbols, String interval, SweepDetectorConfig config,
                            ConvictionConfig conviction, BacktestSummary summary, List<DailyPick> picks,
                            SweepBacktest.JournalResult journal) {

    public DailyBacktest withJournal(SweepBacktest.JournalResult journal) {
        return new DailyBacktest(runId, symbols, interval, config, conviction, summary, picks, journal);
    }
}
