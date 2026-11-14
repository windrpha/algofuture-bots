package com.algofuture.bots.liquiditysweep.application.service;

import com.algofuture.bots.liquiditysweep.application.port.in.RunDailyBacktestUseCase;
import com.algofuture.bots.liquiditysweep.domain.model.ConvictionConfig;
import com.algofuture.bots.liquiditysweep.domain.model.DailyBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * After the close, re-runs the strategy 4.2 backtest over the last days and records its trades in the backtest journal
 * ({@code strategy_trade}, "liquidity sweep 4.2"), so the backtest series keeps growing next to the live signals in
 * {@code live_trade}. The journal upsert is idempotent, so covering several days also fills any day the service missed.
 */
@ApplicationScoped
public class DailyBacktestJournalJob {

    private static final Logger LOG = Logger.getLogger(DailyBacktestJournalJob.class);

    /** Days covered by each run: today plus a week back, enough to catch up after an outage or a long weekend. */
    static final Duration LOOKBACK = Duration.ofDays(7);

    static final SweepDetectorConfig CONFIG = SweepDetectorConfig.DAILY;
    static final ConvictionConfig CONVICTION = ConvictionConfig.WITH_STOP_BUFFER;

    private final RunDailyBacktestUseCase backtest;
    private final List<String> symbols;

    public DailyBacktestJournalJob(RunDailyBacktestUseCase backtest,
                                   @ConfigProperty(name = "alerts.symbols", defaultValue = "NQ.FUT,ES.FUT") List<String> symbols) {
        this.backtest = backtest;
        this.symbols = symbols;
    }

    /** When it runs comes from application.properties ({@code backtest.journal.cron}, {@code .cron-zone}). */
    @Scheduled(identity = "daily-backtest-journal", cron = "{backtest.journal.cron}", timeZone = "{backtest.journal.cron-zone}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void afterTheClose() {
        try {
            run(Instant.now());
        } catch (RuntimeException e) {
            LOG.error("Daily backtest journal run failed", e);
        }
    }

    /** One run up to {@code now}; package-visible so tests can drive the clock. */
    DailyBacktest run(Instant now) {
        ZonedDateTime ct = now.atZone(CONFIG.zone());
        // only completed sessions: before the close, stop at the previous day so no open trade is journaled
        Instant to = ct.toLocalTime().isBefore(CONFIG.sessionEnd())
                ? ct.toLocalDate().atStartOfDay(CONFIG.zone()).toInstant()
                : ct.toLocalDate().atTime(LocalTime.of(23, 59)).atZone(CONFIG.zone()).toInstant();
        Instant from = ct.toLocalDate().atStartOfDay(CONFIG.zone()).minus(LOOKBACK).toInstant();
        DailyBacktest result = backtest.run(symbols, from.atOffset(ZoneOffset.UTC), to.atOffset(ZoneOffset.UTC), "5m",
                CONFIG, CONVICTION, true);
        var journal = result.journal();
        LOG.infof("Daily backtest journal %s: %d trades in range, %d inserted, %d updated, %d unchanged", result.runId(),
                result.picks().size(), journal.inserted(), journal.updated(), journal.unchanged());
        return result;
    }
}
