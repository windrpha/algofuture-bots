package com.algofuture.bots.liquiditysweep.application.port.in;

import com.algofuture.bots.liquiditysweep.domain.model.ConvictionConfig;
import com.algofuture.bots.liquiditysweep.domain.model.DailyBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;

import java.time.OffsetDateTime;
import java.util.List;

public interface RunDailyBacktestUseCase {

    /**
     * Detects sweeps on every symbol, keeps the first one per day with enough conviction (strategy 4.0) and,
     * when {@code journal} is true, records the picked trades in {@code strategy_trade}.
     */
    DailyBacktest run(List<String> symbols, OffsetDateTime from, OffsetDateTime to, String interval,
                      SweepDetectorConfig config, ConvictionConfig conviction, boolean journal);
}
