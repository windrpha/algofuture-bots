package com.algofuture.bots.liquiditysweep.application.port.in;

import com.algofuture.bots.liquiditysweep.domain.model.SweepBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;

import java.time.OffsetDateTime;

public interface RunLiquiditySweepBacktestUseCase {

    /**
     * Detects sweeps over {@code trade_1m_live}, trades the confirmed ones to their exit and,
     * when {@code journal} is true, records them in {@code strategy_trade}.
     */
    SweepBacktest run(String symbol, OffsetDateTime from, OffsetDateTime to, String interval,
                      SweepDetectorConfig config, boolean journal);
}
