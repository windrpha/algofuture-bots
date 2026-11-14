package com.algofuture.bots.liquiditysweep.domain.model;

import java.util.List;

/** Result of running the strategy over a range. {@code journal} is set only when persisted. */
public record SweepBacktest(String runId, ContractSpec contract, String interval, SweepDetectorConfig config,
                            BacktestSummary summary, List<LiquiditySweep> sweeps, List<SweepTrade> trades,
                            JournalResult journal) {

    public record JournalResult(int strategyId, int inserted, int updated, int unchanged) {}

    public SweepBacktest withJournal(JournalResult journal) {
        return new SweepBacktest(runId, contract, interval, config, summary, sweeps, trades, journal);
    }
}
