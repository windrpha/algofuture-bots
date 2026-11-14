package com.algofuture.bots.liquiditysweep.application.port.out;

import com.algofuture.bots.liquiditysweep.domain.model.DailyBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.SessionBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.SweepBacktest;

/** Records the backtest's trades in the {@code strategy_trade} journal. */
public interface TradeJournalPort {

    SweepBacktest.JournalResult record(SweepBacktest backtest);

    /** One-trade-per-session runs: each picked trade is recorded under its own contract. */
    SweepBacktest.JournalResult recordSession(SessionBacktest backtest);

    /** One-trade-per-day conviction runs (4.0): like sessions, plus the reasons each trade qualified. */
    SweepBacktest.JournalResult recordDaily(DailyBacktest backtest);
}
