package com.algofuture.bots.liquiditysweep.application.service;

import com.algofuture.bots.liquiditysweep.application.port.in.RunDailyBacktestUseCase;
import com.algofuture.bots.liquiditysweep.application.port.out.LiquiditySweepBarDataPort;
import com.algofuture.bots.liquiditysweep.application.port.out.TradeJournalPort;
import com.algofuture.bots.liquiditysweep.application.service.SweepMarketLoader.SweepMarket;
import com.algofuture.bots.liquiditysweep.domain.model.BacktestSummary;
import com.algofuture.bots.liquiditysweep.domain.model.ConvictionConfig;
import com.algofuture.bots.liquiditysweep.domain.model.DailyBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.DailyPick;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.bots.liquiditysweep.domain.model.SweepStatus;
import com.algofuture.bots.liquiditysweep.domain.model.SweepTrade;
import com.algofuture.bots.liquiditysweep.domain.service.ConvictionSelector;
import com.algofuture.bots.liquiditysweep.domain.service.ConvictionSelector.Reason;
import com.algofuture.bots.liquiditysweep.domain.service.ConvictionSelector.Scored;
import com.algofuture.bots.liquiditysweep.domain.service.FootprintReasons;
import com.algofuture.bots.liquiditysweep.domain.service.SessionSelector.Candidate;
import com.algofuture.bots.liquiditysweep.domain.service.StopBuffer;
import com.algofuture.bots.liquiditysweep.domain.service.StopWidth;
import com.algofuture.bots.liquiditysweep.domain.service.SweepTradeSimulator;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class RunDailyBacktestService implements RunDailyBacktestUseCase {

    private final SweepMarketLoader loader;
    private final LiquiditySweepBarDataPort barData;
    private final TradeJournalPort journal;

    public RunDailyBacktestService(SweepMarketLoader loader, LiquiditySweepBarDataPort barData, TradeJournalPort journal) {
        this.loader = loader;
        this.barData = barData;
        this.journal = journal;
    }

    @Override
    public DailyBacktest run(List<String> symbols, OffsetDateTime from, OffsetDateTime to, String interval,
                             SweepDetectorConfig config, ConvictionConfig conviction, boolean persist) {
        if (symbols.isEmpty()) throw new IllegalArgumentException("At least one symbol is required");
        Duration barDuration = SweepMarketLoader.parseInterval(interval);
        List<SweepMarket> markets = symbols.stream()
                .map(s -> loader.load(s, from, to, interval, config, false, conviction.swingStrength()))
                .toList();

        List<Candidate> candidates = new ArrayList<>();
        List<Map<LocalDate, Double>> gaps = new ArrayList<>();
        for (int i = 0; i < markets.size(); i++) {
            SweepMarket market = markets.get(i);
            gaps.add(ConvictionSelector.openingGaps(market.bars(), config));
            for (LiquiditySweep sweep : market.sweeps()) {
                if (sweep.status() != SweepStatus.CONFIRMED) continue;
                candidates.add(new Candidate(market.contract(), i, sweep, sweep.confirmedAt().plus(barDuration)));
            }
        }

        Map<Candidate, List<Reason>> footprint = conviction.footprint() == null
                ? Map.of() : footprintReasons(candidates, markets, barDuration, conviction);

        Map<Candidate, Double> riskAtr = new HashMap<>();
        if (conviction.maxRiskAtr() > 0) {
            for (Candidate c : candidates) {
                riskAtr.put(c, StopWidth.riskOverAtr(c.sweep(), markets.get(c.symbolOrder()).bars()));
            }
        }

        List<DailyPick> picks = new ArrayList<>();
        for (Scored s : ConvictionSelector.onePerDay(candidates, gaps, footprint, riskAtr, config, conviction)) {
            SweepMarket market = markets.get(s.candidate().symbolOrder());
            double buffer = conviction.stopBufferTicks(market.contract().root()) * market.contract().tickSize();
            LiquiditySweep sweep = StopBuffer.apply(s.candidate().sweep(), buffer, config.rewardRisk());
            SweepTrade trade = new SweepTradeSimulator(config, market.contract(), barDuration)
                    .simulate(market.bars(), List.of(sweep)).get(0);
            picks.add(new DailyPick(market.contract(), trade, s.reasons()));
        }

        List<LiquiditySweep> allSweeps = markets.stream().flatMap(m -> m.sweeps().stream()).toList();
        String roots = markets.stream().map(m -> m.contract().root().toLowerCase(Locale.ROOT)).collect(Collectors.joining("-"));
        String runId = "%s-%s-%s-%s-%s".formatted(conviction.strategyName().replace(' ', '-'), roots, interval,
                from.toLocalDate().format(RunLiquiditySweepBacktestService.RUN_DATE),
                to.toLocalDate().format(RunLiquiditySweepBacktestService.RUN_DATE));
        DailyBacktest backtest = new DailyBacktest(runId, symbols, interval, config, conviction,
                BacktestSummary.of(allSweeps, picks.stream().map(DailyPick::trade).toList()), picks, null);

        return persist ? backtest.withJournal(journal.recordDaily(backtest)) : backtest;
    }

    /** Loads only each sweep's excursion minutes (pierce → reclaim candle): the footprint is ~17KB per minute. */
    private Map<Candidate, List<Reason>> footprintReasons(List<Candidate> candidates, List<SweepMarket> markets,
                                                         Duration barDuration, ConvictionConfig conviction) {
        Map<Candidate, List<Reason>> out = new HashMap<>();
        for (Candidate c : candidates) {
            SweepMarket market = markets.get(c.symbolOrder());
            LiquiditySweep sweep = c.sweep();
            List<TradesBar1m> minutes = barData.findMinutesWithFootprint(market.contract().sourceSymbol(),
                    sweep.piercedAt().atOffset(ZoneOffset.UTC), sweep.sweepAt().plus(barDuration).atOffset(ZoneOffset.UTC));
            out.put(c, FootprintReasons.of(sweep, minutes, market.contract().tickSize(), conviction.footprint()));
        }
        return out;
    }
}
