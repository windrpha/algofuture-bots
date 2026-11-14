package com.algofuture.bots.liquiditysweep.application.service;

import com.algofuture.bots.liquiditysweep.application.port.in.RunLiquiditySweepBacktestUseCase;
import com.algofuture.bots.liquiditysweep.application.port.out.TradeJournalPort;
import com.algofuture.bots.liquiditysweep.application.service.SweepMarketLoader.SweepMarket;
import com.algofuture.bots.liquiditysweep.domain.model.BacktestSummary;
import com.algofuture.bots.liquiditysweep.domain.model.SweepBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.bots.liquiditysweep.domain.model.SweepTrade;
import com.algofuture.bots.liquiditysweep.domain.service.SweepTradeSimulator;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@ApplicationScoped
public class RunLiquiditySweepBacktestService implements RunLiquiditySweepBacktestUseCase {

    static final DateTimeFormatter RUN_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final SweepMarketLoader loader;
    private final TradeJournalPort journal;

    public RunLiquiditySweepBacktestService(SweepMarketLoader loader, TradeJournalPort journal) {
        this.loader = loader;
        this.journal = journal;
    }

    @Override
    public SweepBacktest run(String symbol, OffsetDateTime from, OffsetDateTime to, String interval,
                             SweepDetectorConfig config, boolean persist) {
        SweepMarket market = loader.load(symbol, from, to, interval, config, false);
        List<SweepTrade> trades = new SweepTradeSimulator(config, market.contract(), SweepMarketLoader.parseInterval(interval))
                .simulate(market.bars(), market.sweeps());

        String runId = "%s-%s-%s-%s-%s".formatted(
                config.strategyName().replace(' ', '-'), market.contract().root().toLowerCase(Locale.ROOT), interval,
                from.toLocalDate().format(RUN_DATE), to.toLocalDate().format(RUN_DATE));
        SweepBacktest backtest = new SweepBacktest(runId, market.contract(), interval, config,
                BacktestSummary.of(market.sweeps(), trades), market.sweeps(), trades, null);

        return persist ? backtest.withJournal(journal.record(backtest)) : backtest;
    }
}
