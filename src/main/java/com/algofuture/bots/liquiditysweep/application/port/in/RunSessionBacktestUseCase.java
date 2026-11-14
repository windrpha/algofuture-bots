package com.algofuture.bots.liquiditysweep.application.port.in;

import com.algofuture.bots.liquiditysweep.domain.model.SessionBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.SessionConfig;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;

import java.time.OffsetDateTime;
import java.util.List;

public interface RunSessionBacktestUseCase {

    /**
     * Detects sweeps on every symbol, keeps the first qualifying one per session (strategy 3.0) and, when
     * {@code journal} is true, records the picked trades in {@code strategy_trade}.
     */
    SessionBacktest run(List<String> symbols, OffsetDateTime from, OffsetDateTime to, String interval,
                        SweepDetectorConfig config, SessionConfig session, boolean journal);
}
