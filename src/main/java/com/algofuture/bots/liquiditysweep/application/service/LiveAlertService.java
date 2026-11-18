package com.algofuture.bots.liquiditysweep.application.service;

import com.algofuture.bots.liquiditysweep.application.port.in.RunDailyBacktestUseCase;
import com.algofuture.bots.liquiditysweep.application.port.out.AccountsPort;
import com.algofuture.bots.liquiditysweep.application.port.out.AlertPort;
import com.algofuture.bots.liquiditysweep.application.port.out.LiquiditySweepBarDataPort;
import com.algofuture.bots.liquiditysweep.application.port.out.LiveTradePort;
import com.algofuture.bots.liquiditysweep.domain.model.ConvictionConfig;
import com.algofuture.bots.liquiditysweep.domain.model.DailyBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.DailyPick;
import com.algofuture.bots.liquiditysweep.domain.model.ExitType;
import com.algofuture.bots.liquiditysweep.domain.model.FundedAccount;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.bots.liquiditysweep.domain.model.SweepTrade;
import com.algofuture.bots.liquiditysweep.domain.service.AlertMessages;
import com.algofuture.bots.liquiditysweep.domain.service.MicroSizing;
import com.algofuture.bots.liquiditysweep.domain.service.MicroSizing.Size;
import com.algofuture.shared.journal.domain.model.LiveTradeRecord;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.scheduler.Trigger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Live alerts for strategy 4.3: every minute of the session it re-runs the exact backtest logic over the CLOSED 5m
 * candles up to now and, when today's trade appears, records and announces it once PER FUNDED ACCOUNT, each sized in
 * micros for that account's own risk (or skipped for it); then the exit per account that took it, and one reminder at
 * 14:55 if it is still open. Re-running the backtest instead of a separate live engine keeps the alerts identical to
 * what was tested: every rule is causal, so a pick never changes once its entry candle has closed.
 *
 * <p>The state lives in {@code live_trade}, not in memory: a restart picks up where it left off, and since every write
 * is conditional, a second running instance never sends a message twice.
 */
@ApplicationScoped
public class LiveAlertService {

    private static final Logger LOG = Logger.getLogger(LiveAlertService.class);
    private static final Duration BAR = Duration.ofMinutes(5);
    /** The 15:00 flat needs the 15:00 candle, closed from 15:05 on: the loop keeps running a few minutes past that. */
    private static final LocalTime FIRST_RUN = LocalTime.of(8, 31), LAST_RUN = LocalTime.of(15, 10);
    private static final LocalTime CLOSE_REMINDER = LocalTime.of(14, 55);
    /** A pick first seen later than this after its entry (e.g. after a restart) is not announced: too late to take. */
    private static final Duration STALE = Duration.ofMinutes(10);

    static final SweepDetectorConfig CONFIG = SweepDetectorConfig.DAILY;
    static final ConvictionConfig CONVICTION = ConvictionConfig.WITH_STOP_WIDTH;

    private final RunDailyBacktestUseCase backtest;
    private final LiquiditySweepBarDataPort barData;
    private final AlertPort alerts;
    private final AccountsPort accounts;
    private final LiveTradePort trades;
    private final boolean enabled;
    private final List<String> symbols;
    private final Scheduler scheduler;

    /** No market data for this long in session is announced (once per outage) and reported by the readiness check. */
    static final Duration DATA_STALE_AFTER = Duration.ofMinutes(5);

    /** Only per-process niceties: warnings already sent today and a pick already known to be too late. */
    private LocalDate day;
    private boolean warningSent, staleToday, dataWarningSent;
    /** For the health checks: when the loop started, last finished a run, last saw complete data, last failed. */
    private final Instant startedAt = Instant.now();
    private volatile Instant lastRun, lastData, lastFailure;
    /** First evaluation of this process: the baseline for "no data since" when none has arrived yet. */
    private Instant firstTick;
    private volatile String lastError;

    public LiveAlertService(RunDailyBacktestUseCase backtest, LiquiditySweepBarDataPort barData, AlertPort alerts,
                            AccountsPort accounts, LiveTradePort trades, Scheduler scheduler,
                            @ConfigProperty(name = "alerts.enabled", defaultValue = "false") boolean enabled,
                            @ConfigProperty(name = "alerts.symbols", defaultValue = "NQ.FUT,ES.FUT") List<String> symbols) {
        this.backtest = backtest;
        this.barData = barData;
        this.alerts = alerts;
        this.accounts = accounts;
        this.trades = trades;
        this.scheduler = scheduler;
        this.enabled = enabled;
        this.symbols = symbols;
    }

    /** Identity of the scheduled job, to look up its next run for the status endpoint. */
    public static final String JOB = "live-alerts";

    /** States at startup whether the loop is on and when it runs: a disabled loop never logs anything else. */
    void onStart(@Observes StartupEvent event) {
        var config = ConfigProvider.getConfig();
        String schedule = "cron '%s' (%s)".formatted(config.getValue("alerts.cron", String.class),
                config.getValue("alerts.cron-zone", String.class));
        if (enabled) {
            LOG.infof("Live alerts enabled: %s, symbols %s, telegram configured=%s", schedule, symbols, alerts.configured());
        } else {
            LOG.warnf("Live alerts DISABLED (set ALERTS_ENABLED=true to turn them on): %s", schedule);
        }
    }

    /** When it runs comes from application.properties ({@code alerts.cron}, {@code alerts.cron-zone}), read at startup. */
    @Scheduled(identity = JOB, cron = "{alerts.cron}", timeZone = "{alerts.cron-zone}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void everyMinute() {
        if (!enabled) return;
        Instant now = Instant.now();
        try {
            String outcome = tick(now);
            lastRun = now;
            LOG.infof("Live alert run: %s (%d ms)", outcome, Duration.between(now, Instant.now()).toMillis());
        } catch (RuntimeException e) {
            lastFailure = now;
            lastError = e.getMessage();
            LOG.error("Live alert run failed", e);
        }
    }

    /** One evaluation at {@code now}, returning what it did for the log; package-visible so tests can drive the clock. */
    synchronized String tick(Instant now) {
        ZonedDateTime ct = now.atZone(CONFIG.zone());
        LocalTime time = ct.toLocalTime();
        if (ct.getDayOfWeek() == DayOfWeek.SATURDAY || ct.getDayOfWeek() == DayOfWeek.SUNDAY) return "weekend";
        if (time.isBefore(FIRST_RUN) || time.isAfter(LAST_RUN)) return "outside the session window";
        if (firstTick == null) firstTick = now;
        if (!ct.toLocalDate().equals(day)) {
            day = ct.toLocalDate();
            warningSent = staleToday = false;
        }
        List<LiveTradeRecord> today = trades.findDay(day);
        if (!today.isEmpty() && today.stream().noneMatch(LiveTradeRecord::awaitsOutcome)) return "today's trade done: " + outcome(today);

        Instant to = Instant.ofEpochSecond(now.getEpochSecond() / BAR.toSeconds() * BAR.toSeconds());
        Map<String, Double> lastClose = lastClosesIfComplete(to);
        if (lastClose.isEmpty()) {                                   // the last closed 5m candle is not fully in yet
            Instant since = lastData != null ? lastData : firstTick;
            if (!dataWarningSent && Duration.between(since, now).compareTo(DATA_STALE_AFTER) > 0
                    && time.isAfter(FIRST_RUN.plus(DATA_STALE_AFTER))) {
                alerts.send(AlertMessages.noMarketData(Duration.between(since, now).toMinutes()));
                dataWarningSent = true;
                return "no market data for the candle ending " + to.atZone(CONFIG.zone()).toLocalTime() + " CT, warning sent";
            }
            return "waiting for the candle ending " + to.atZone(CONFIG.zone()).toLocalTime() + " CT";
        }
        lastData = now;
        dataWarningSent = false;

        DailyBacktest run = backtest.run(symbols, to.minus(Duration.ofDays(7)).atOffset(ZoneOffset.UTC),
                to.atOffset(ZoneOffset.UTC), "5m", CONFIG, CONVICTION, false);
        Optional<DailyPick> found = run.picks().stream()
                .filter(p -> CONFIG.dayOf(p.trade().entryAt()).equals(day))
                .findFirst();
        if (found.isEmpty()) return "no pick today yet";
        DailyPick pick = found.get();

        boolean announced = false;
        double last = lastClose.get(pick.contract().sourceSymbol());
        if (today.isEmpty()) {
            if (staleToday) {                                        // still followed, so the log shows how it ends
                return "today's signal (%s) was too late, no alert sent; %s".formatted(describe(pick), progress(pick, last));
            }
            if (Duration.between(pick.trade().entryAt(), now).compareTo(STALE) > 0) {
                staleToday = true;
                return "today's signal (%s) was first seen at %s CT, %d min after its entry (limit %d min): too late to take it, no alert sent; %s"
                        .formatted(describe(pick), ct.toLocalTime().withSecond(0).withNano(0),
                                Duration.between(pick.trade().entryAt(), now).toMinutes(), STALE.toMinutes(),
                                progress(pick, last));
            }
            announce(pick, last);
            announced = true;
            today = trades.findDay(day);
        }

        SweepTrade t = pick.trade();
        String what = "%s pick (%s)".formatted(announced ? "announced" : "tracking", describe(pick));
        List<LiveTradeRecord> open = today.stream().filter(LiveTradeRecord::isOpen).toList();
        List<LiveTradeRecord> pendingOutcome = today.stream().filter(LiveTradeRecord::awaitsOutcome).toList();
        if (t.exitType() != ExitType.OPEN) {
            for (LiveTradeRecord row : pendingOutcome) {
                if (!row.isOpen()) {                                 // skipped: still tell the trader how it ended
                    if (trades.recordSkippedOutcome(row.id(), t.exitType().code, t.exitAt(), t.exitPrice())) {
                        alerts.send(AlertMessages.skippedExit(row, t.exitType(), t.exitPrice(), t.exitAt()));
                    }
                    continue;
                }
                double pnl = (row.isLong() ? t.exitPrice() - row.entryEstimate() : row.entryEstimate() - t.exitPrice())
                        * MicroSizing.usdPerPoint(row.symbol(), row.contracts());
                if (trades.close(row.id(), t.exitType().code, t.exitAt(), t.exitPrice(), pnl)) {
                    alerts.send(AlertMessages.exit(row, t.exitType(), t.exitPrice(), t.exitAt(), pnl));
                }
            }
            return what + ", exit " + t.exitType() + " for " + pendingOutcome.size() + " row(s)";
        }
        if (open.isEmpty()) return what + ", no open trade; " + progress(pick, last);
        if (!time.isBefore(CLOSE_REMINDER)) {
            List<LiveTradeRecord> pending = open.stream().filter(r -> !r.reminderSent()).toList();
            if (!pending.isEmpty() && trades.markReminderSent(pending.stream().map(LiveTradeRecord::id).toList()) > 0) {
                alerts.send(AlertMessages.closeReminder(pick.contract().root(),
                        t.isLong(), pending.stream().map(LiveTradeRecord::accountLabel).toList()));
                return what + ", close reminder sent";
            }
        }
        return what + ", " + open.size() + " open trade(s); " + progress(pick, last);
    }

    /** Records the signal for every enabled account and sends each message only if THIS call recorded it. */
    private void announce(DailyPick pick, double lastPrice) {
        List<FundedAccount> active;
        try {
            active = accounts.enabledAccounts();
        } catch (RuntimeException e) {
            warnOnce(AlertMessages.accountsProblem(e.getMessage()));
            return;                                                  // retried next minute, within the stale window
        }
        if (active.isEmpty()) {
            warnOnce(AlertMessages.noAccounts());
            return;
        }
        SweepTrade t = pick.trade();
        String root = pick.contract().root();
        String strategy = CONVICTION.strategyName().replace("liquidity sweep ", "LS ");
        double signalRisk = Math.abs(t.entryPrice() - t.stopLoss());
        double riskFromNow = Math.abs(lastPrice - t.stopLoss()), rewardFromNow = Math.abs(t.takeProfit() - lastPrice);
        List<String> reasons = pick.reasons().stream().map(Enum::name).toList();
        for (FundedAccount account : active) {
            Optional<Size> sized = MicroSizing.of(root, signalRisk, account.maxRisk(), account.maxRisk());
            LiveTradeRecord row = new LiveTradeRecord(null, day, account.id(), account.label(), CONVICTION.strategyName(),
                    root, MicroSizing.microOf(root), sized.map(Size::contracts).orElse(0), t.isLong() ? "long" : "short",
                    t.entryAt(), t.entryPrice(), t.stopLoss(), t.takeProfit(), lastPrice,
                    sized.map(s -> s.usd(riskFromNow)).orElse(0.0), sized.map(s -> s.usd(rewardFromNow)).orElse(0.0),
                    reasons, sized.isPresent() ? LiveTradeRecord.OPEN : LiveTradeRecord.SKIPPED,
                    sized.isPresent() ? null : "1 micro risks more than the account's max risk per trade",
                    null, null, null, null, false);
            if (!trades.insert(row)) continue;                       // already recorded (restart or another instance)
            alerts.send(sized.isPresent()
                    ? AlertMessages.entry(strategy, pick, account, sized.get(), lastPrice)
                    : AlertMessages.skipped(strategy, pick, account, MicroSizing.single(root, signalRisk)));
        }
    }

    private static String describe(DailyPick pick) {
        SweepTrade t = pick.trade();
        return "%s %s, entry %s at %s CT, SL %s, TP %s".formatted(pick.contract().root(), t.isLong() ? "long" : "short",
                t.entryPrice(), t.entryAt().atZone(CONFIG.zone()).toLocalTime(), t.stopLoss(), t.takeProfit());
    }

    /** Where the trade stands: still open against the last close, or how and when it exited, in points. */
    private static String progress(DailyPick pick, double last) {
        SweepTrade t = pick.trade();
        if (t.exitType() == ExitType.OPEN) {
            return "still open, last %s (%+.2f pts)".formatted(last, t.isLong() ? last - t.entryPrice() : t.entryPrice() - last);
        }
        double pts = t.isLong() ? t.exitPrice() - t.entryPrice() : t.entryPrice() - t.exitPrice();
        return "closed by %s at %s at %s CT (%+.2f pts)".formatted(t.exitType(), t.exitPrice(),
                t.exitAt().atZone(CONFIG.zone()).toLocalTime(), pts);
    }

    /** How the day's recorded signal ended for each account (closed with its exit, or skipped and why), from live_trade. */
    static String outcome(List<LiveTradeRecord> rows) {
        LiveTradeRecord first = rows.get(0);
        return "%s %s, %s".formatted(first.symbol(), first.side(), rows.stream().map(r -> {
            String exit = Arrays.stream(ExitType.values()).filter(e -> e.code.equals(r.exitType())).findFirst()
                    .map(Enum::name).orElse(r.exitType());
            if (!LiveTradeRecord.CLOSED.equals(r.status())) {
                String ended = r.exitType() == null ? "" : ", signal ended by %s at %s at %s CT".formatted(exit, r.exitPrice(),
                        r.exitTime().atZone(CONFIG.zone()).toLocalTime());
                return "%s %s (%s)%s".formatted(r.accountId(), r.status(), r.skipReason(), ended);
            }
            return "%s closed by %s at %s at %s CT (pnl %s$%.0f)".formatted(r.accountId(), exit, r.exitPrice(),
                    r.exitTime().atZone(CONFIG.zone()).toLocalTime(), r.pnlUsd() < 0 ? "-" : "+", Math.abs(r.pnlUsd()));
        }).collect(Collectors.joining("; ")));
    }

    private void warnOnce(String message) {
        if (warningSent) return;
        alerts.send(message);
        warningSent = true;
    }

    /** Per symbol, the close of the last minute of the candle ending at {@code to}; empty until every symbol has it. */
    private Map<String, Double> lastClosesIfComplete(Instant to) {
        Map<String, Double> closes = new HashMap<>();
        for (String symbol : symbols) {
            List<TradesBar1m> last = barData.findBars(symbol, to.minus(Duration.ofMinutes(1)).atOffset(ZoneOffset.UTC),
                    to.atOffset(ZoneOffset.UTC), "1m");
            if (last.isEmpty()) return Map.of();
            closes.put(symbol, last.get(last.size() - 1).close());
        }
        return closes;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Whether {@code now} falls in the window the loop works in (weekdays 08:31–15:10 Chicago). */
    public static boolean inSession(Instant now) {
        ZonedDateTime ct = now.atZone(CONFIG.zone());
        LocalTime time = ct.toLocalTime();
        return ct.getDayOfWeek() != DayOfWeek.SATURDAY && ct.getDayOfWeek() != DayOfWeek.SUNDAY
                && !time.isBefore(FIRST_RUN) && !time.isAfter(LAST_RUN);
    }

    /** Last finished run, or the start time if none yet (a fresh process gets the same grace as a running one). */
    public Instant lastRunOrStart() {
        Instant run = lastRun;
        return run == null ? startedAt : run;
    }

    public Instant lastFailure() {
        return lastFailure;
    }

    public String lastError() {
        return lastError;
    }

    /** For the status endpoint: today's recorded signals and, per account, the risk the next trade would take. */
    public synchronized String status() {
        LocalDate today = LocalDate.now(CONFIG.zone());
        Trigger job = scheduler == null ? null : scheduler.getScheduledJob(JOB);
        StringBuilder out = new StringBuilder("enabled=%s, telegram=%s, day=%s, nextRun=%s%n".formatted(enabled,
                alerts.configured(), today, job == null || job.getNextFireTime() == null ? "-"
                        : job.getNextFireTime().atZone(CONFIG.zone()).toLocalDateTime() + " CT"));
        try {
            for (FundedAccount a : accounts.enabledAccounts()) {
                out.append("  account %s [%s]: room $%.0f, max risk per trade $%.0f%n".formatted(a.label(), a.id(), a.room(), a.maxRisk()));
            }
            for (LiveTradeRecord r : trades.findDay(today)) {
                out.append("  trade %s %s %s x%d %s: %s%s%n".formatted(r.accountId(), r.side(), r.symbol(), r.contracts(),
                        r.micro(), r.status(), r.pnlUsd() == null ? "" : " pnl $%.0f".formatted(r.pnlUsd())));
            }
        } catch (RuntimeException e) {
            out.append("  database problem: ").append(e.getMessage()).append('\n');
        }
        return out.toString();
    }
}
