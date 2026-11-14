package com.algofuture.bots.liquiditysweep.application.service;

import com.algofuture.bots.liquiditysweep.application.port.in.RunSessionBacktestUseCase;
import com.algofuture.bots.liquiditysweep.application.port.out.TradeJournalPort;
import com.algofuture.bots.liquiditysweep.application.service.SweepMarketLoader.SweepMarket;
import com.algofuture.bots.liquiditysweep.domain.model.BacktestSummary;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SessionBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.SessionConfig;
import com.algofuture.bots.liquiditysweep.domain.model.SessionPick;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.bots.liquiditysweep.domain.model.SweepStatus;
import com.algofuture.bots.liquiditysweep.domain.model.SweepTrade;
import com.algofuture.bots.liquiditysweep.domain.service.RangeTarget;
import com.algofuture.bots.liquiditysweep.domain.service.SessionSelector;
import com.algofuture.bots.liquiditysweep.domain.service.SessionSelector.Candidate;
import com.algofuture.bots.liquiditysweep.domain.service.SmtConfirmation;
import com.algofuture.bots.liquiditysweep.domain.service.SweepTradeSimulator;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@ApplicationScoped
public class RunSessionBacktestService implements RunSessionBacktestUseCase {

    private final SweepMarketLoader loader;
    private final TradeJournalPort journal;

    public RunSessionBacktestService(SweepMarketLoader loader, TradeJournalPort journal) {
        this.loader = loader;
        this.journal = journal;
    }

    @Override
    public SessionBacktest run(List<String> symbols, OffsetDateTime from, OffsetDateTime to, String interval,
                               SweepDetectorConfig config, SessionConfig session, boolean persist) {
        if (symbols.isEmpty()) throw new IllegalArgumentException("At least one symbol is required");
        if (session.smtConfirmation() && symbols.size() < 2) {
            throw new IllegalArgumentException("SMT confirmation needs at least two correlated symbols");
        }
        if (session.rangeTarget() && config.openingRangeMinutes() == 0) {
            throw new IllegalArgumentException("rangeTarget needs an opening range (e.g. openingRange=15)");
        }
        Duration barDuration = SweepMarketLoader.parseInterval(interval);
        List<SweepMarket> markets = symbols.stream()
                .map(s -> loader.load(s, from, to, interval, config, session.smtConfirmation()))
                .toList();

        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < markets.size(); i++) {
            SweepMarket market = markets.get(i);
            for (LiquiditySweep sweep : market.sweeps()) {
                if (sweep.status() != SweepStatus.CONFIRMED) continue;
                Instant entryAt = sweep.confirmedAt().plus(barDuration);   // fills at the confirmation close
                if (entryAt.atZone(config.zone()).toLocalTime().isAfter(session.lastEntry())) continue;
                if (session.smtConfirmation() && !confirmedElsewhere(sweep, i, markets, config)) continue;
                candidates.add(new Candidate(market.contract(), i, sweep, entryAt));
            }
        }

        List<SessionPick> picks = new ArrayList<>();
        for (Candidate c : SessionSelector.onePerSession(candidates, config.zone())) {
            SweepMarket market = markets.get(c.symbolOrder());
            LiquiditySweep sweep = session.rangeTarget()
                    ? RangeTarget.apply(c.sweep(), market.levels().getOrDefault(config.dayOf(c.entryAt()), List.of()))
                    : c.sweep();
            SweepTrade trade = new SweepTradeSimulator(config, market.contract(), barDuration)
                    .simulate(market.bars(), List.of(sweep)).get(0);
            picks.add(new SessionPick(market.contract(), trade));
        }

        List<LiquiditySweep> allSweeps = markets.stream().flatMap(m -> m.sweeps().stream()).toList();
        String roots = markets.stream().map(m -> m.contract().root().toLowerCase(Locale.ROOT)).collect(Collectors.joining("-"));
        String runId = "%s-%s-%s-%s-%s".formatted(session.strategyName(config).replace(' ', '-'), roots, interval,
                from.toLocalDate().format(RunLiquiditySweepBacktestService.RUN_DATE),
                to.toLocalDate().format(RunLiquiditySweepBacktestService.RUN_DATE));
        SessionBacktest backtest = new SessionBacktest(runId, symbols, interval, config, session,
                BacktestSummary.of(allSweeps, picks.stream().map(SessionPick::trade).toList()), picks, null);

        return persist ? backtest.withJournal(journal.recordSession(backtest)) : backtest;
    }

    /** Whether any other contract swept its own level of the same type during this sweep's excursion. */
    private static boolean confirmedElsewhere(LiquiditySweep sweep, int self, List<SweepMarket> markets,
                                              SweepDetectorConfig config) {
        for (int j = 0; j < markets.size(); j++) {
            if (j == self) continue;
            SweepMarket other = markets.get(j);
            List<KeyLevel> levels = other.rawLevels().getOrDefault(config.dayOf(sweep.sweepAt()), List.of());
            if (SmtConfirmation.confirmed(sweep, other.bars(), levels)) return true;
        }
        return false;
    }
}
