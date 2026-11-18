package com.algofuture.bots.liquiditysweep.application.service;

import com.algofuture.bots.liquiditysweep.application.port.in.RunDailyBacktestUseCase;
import com.algofuture.bots.liquiditysweep.application.port.out.AccountsPort;
import com.algofuture.bots.liquiditysweep.application.port.out.AlertPort;
import com.algofuture.bots.liquiditysweep.application.port.out.LiquiditySweepBarDataPort;
import com.algofuture.bots.liquiditysweep.application.port.out.LiveTradePort;
import com.algofuture.bots.liquiditysweep.domain.model.BacktestSummary;
import com.algofuture.bots.liquiditysweep.domain.model.ContractSpec;
import com.algofuture.bots.liquiditysweep.domain.model.DailyBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.DailyPick;
import com.algofuture.bots.liquiditysweep.domain.model.ExitType;
import com.algofuture.bots.liquiditysweep.domain.model.FundedAccount;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel.Type;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDirection;
import com.algofuture.bots.liquiditysweep.domain.model.SweepStatus;
import com.algofuture.bots.liquiditysweep.domain.model.SweepTrade;
import com.algofuture.bots.liquiditysweep.domain.model.TradeResult;
import com.algofuture.bots.liquiditysweep.domain.service.ConvictionSelector.Reason;
import com.algofuture.shared.journal.domain.model.LiveTradeRecord;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveAlertServiceTest {

    private static final ContractSpec NQ = ContractSpec.of("NQ.FUT", 0.25);
    private static final Instant ENTRY = Instant.parse("2026-11-11T15:05:00Z");          // 09:05 CT
    /** NYS 25k with $637 of room → $127 per trade; a fresh 50k with its full $2,000 → $400. */
    private static final FundedAccount NYS = new FundedAccount("nys-25k", "NYS", "The Thunder 25k", 1250, 612.9, 500,
            0.20, null, true);
    private static final FundedAccount FRESH = new FundedAccount("other-50k", "Otra", "50k", 2000, 0, 1000, 0.20, null, true);

    /** What the fake backtest returns: null = no pick yet. */
    private SweepTrade trade;
    private final List<OffsetDateTime> backtestTo = new ArrayList<>();
    private final List<String> sent = new ArrayList<>();
    private boolean dataComplete = true;
    private double lastPrice = 21620;                                // = the signal's entry unless a test moves it
    private List<FundedAccount> accounts = List.of(NYS);
    private boolean accountsBroken = false;
    private final InMemoryLiveTrades store = new InMemoryLiveTrades();
    private LiveAlertService service;

    /** live_trade in memory, with the same one-per-account-per-day rule and conditional updates as the table. */
    static final class InMemoryLiveTrades implements LiveTradePort {
        final List<LiveTradeRecord> rows = new ArrayList<>();
        private long nextId = 1;

        public synchronized List<LiveTradeRecord> findDay(LocalDate day) {
            return rows.stream().filter(r -> r.tradeDay().equals(day)).toList();
        }

        public synchronized boolean insert(LiveTradeRecord t) {
            if (rows.stream().anyMatch(r -> r.tradeDay().equals(t.tradeDay()) && r.accountId().equals(t.accountId()))) return false;
            rows.add(with(t, nextId++, t.status(), null, null, null, null, false));
            return true;
        }

        public synchronized boolean close(long id, String exitType, Instant exitTime, double exitPrice, double pnl) {
            for (int i = 0; i < rows.size(); i++) {
                LiveTradeRecord r = rows.get(i);
                if (r.id() == id && r.isOpen()) {
                    rows.set(i, with(r, id, LiveTradeRecord.CLOSED, exitType, exitTime, exitPrice, pnl, r.reminderSent()));
                    return true;
                }
            }
            return false;
        }

        public synchronized boolean recordSkippedOutcome(long id, String exitType, Instant exitTime, double exitPrice) {
            for (int i = 0; i < rows.size(); i++) {
                LiveTradeRecord r = rows.get(i);
                if (r.id() == id && LiveTradeRecord.SKIPPED.equals(r.status()) && r.exitType() == null) {
                    rows.set(i, with(r, id, r.status(), exitType, exitTime, exitPrice, null, r.reminderSent()));
                    return true;
                }
            }
            return false;
        }

        public synchronized int markReminderSent(List<Long> ids) {
            int n = 0;
            for (int i = 0; i < rows.size(); i++) {
                LiveTradeRecord r = rows.get(i);
                if (ids.contains(r.id()) && !r.reminderSent() && r.isOpen()) {
                    rows.set(i, with(r, r.id(), r.status(), null, null, null, null, true));
                    n++;
                }
            }
            return n;
        }

        private static LiveTradeRecord with(LiveTradeRecord r, long id, String status, String exitType, Instant exitTime,
                                            Double exitPrice, Double pnl, boolean reminder) {
            return new LiveTradeRecord(id, r.tradeDay(), r.accountId(), r.accountLabel(), r.strategy(), r.symbol(), r.micro(),
                    r.contracts(), r.side(), r.signalTime(), r.signalPrice(), r.stopLoss(), r.takeProfit(), r.entryEstimate(),
                    r.riskUsd(), r.rewardUsd(), r.reasons(), status, r.skipReason(), exitType, exitTime, exitPrice, pnl, reminder);
        }
    }

    private static SweepTrade trade(double stop, ExitType exit, Double r) {
        LiquiditySweep sweep = new LiquiditySweep("NQ.FUT", SweepDirection.BEARISH, SweepStatus.CONFIRMED, 21690,
                Type.HOUR_HIGH, ENTRY, List.of("H1H"), ENTRY, ENTRY, stop, 21650, ENTRY, 21620.0, stop, 0.0);
        double target = 21620 - 3 * (stop - 21620);
        return new SweepTrade(sweep, ENTRY, 21620, stop, target, exit == ExitType.OPEN ? null : ENTRY.plusSeconds(3600),
                exit == ExitType.OPEN ? null : (r != null && r > 0 ? target : stop), exit,
                exit == ExitType.OPEN ? TradeResult.OPEN : (r > 0 ? TradeResult.WIN : TradeResult.LOSS), 3.0, r, null, null);
    }

    private LiveAlertService newService() {
        RunDailyBacktestUseCase backtest = (symbols, from, to, interval, config, conviction, journal) -> {
            backtestTo.add(to);
            List<DailyPick> picks = trade == null ? List.of()
                    : List.of(new DailyPick(NQ, trade, List.of(Reason.KEY_LEVEL, Reason.WITH_GAP)));
            return new DailyBacktest("run", symbols, interval, config, conviction,
                    BacktestSummary.of(List.of(), List.of()), picks, null);
        };
        LiquiditySweepBarDataPort barData = new LiquiditySweepBarDataPort() {
            public List<TradesBar1m> findBars(String symbol, OffsetDateTime from, OffsetDateTime to, String interval) {
                return dataComplete ? List.of(new TradesBar1m(from.toInstant(), symbol, 0, 0, 0, lastPrice, 0, 0, 0, 0, 0, 0, 0, Map.of()))
                        : List.of();
            }
            public List<TradesBar1m> findMinutesWithFootprint(String s, OffsetDateTime f, OffsetDateTime t) { return List.of(); }
            public Optional<Double> findTickSize(String symbol) { return Optional.of(0.25); }
        };
        AlertPort alerts = new AlertPort() {
            public boolean configured() { return true; }
            public void send(String text) { sent.add(text); }
        };
        AccountsPort accountsPort = () -> {
            if (accountsBroken) throw new IllegalStateException("db down");
            return accounts;
        };
        return new LiveAlertService(backtest, barData, alerts, accountsPort, store, null, true, List.of("NQ.FUT", "ES.FUT"));
    }

    @BeforeEach
    void setUp() {
        service = newService();
    }

    @Test
    void entryOnce_thenExitOnce_andBothAreRecorded() {
        trade = trade(21680, ExitType.OPEN, null);                   // 60 points: 1 MNQ, $120 ≤ $127
        service.tick(Instant.parse("2026-11-11T15:06:20Z"));
        service.tick(Instant.parse("2026-11-11T15:07:20Z"));
        assertEquals(1, sent.size());
        String m = sent.get(0);
        assertTrue(m.startsWith("SELL NQ — NYS · The Thunder 25k"), m);
        assertTrue(m.contains("Size: 1 MNQ (Micro Nasdaq)"), m);
        assertTrue(m.contains("Stop loss: 21,680.00 → you lose $120"), m);
        assertTrue(m.contains("Take profit: 21,440.00 → you make $360"), m);
        assertTrue(m.contains("Risk: 19% of the $637 left on this account"), m);
        LiveTradeRecord row = store.rows.get(0);
        assertEquals(LiveTradeRecord.OPEN, row.status());
        assertEquals(1, row.contracts());
        assertEquals(120, row.riskUsd(), 1e-9);

        trade = trade(21680, ExitType.TARGET, 3.0);
        service.tick(Instant.parse("2026-11-11T16:10:20Z"));
        service.tick(Instant.parse("2026-11-11T16:11:20Z"));
        assertEquals(2, sent.size());
        assertTrue(sent.get(1).startsWith("✅ TAKE PROFIT HIT · SELL NQ — NYS · The Thunder 25k"), sent.get(1));
        assertTrue(sent.get(1).contains("Result: WIN, you make $360 with 1 MNQ (+180.00 pts, +3.0R)"), sent.get(1));
        assertTrue(sent.get(1).contains("Entry ≈ 21,620.00 · exit 21,440.00 at 10:05 CT"), sent.get(1));
        assertEquals(LiveTradeRecord.CLOSED, store.rows.get(0).status());
        assertEquals("tp", store.rows.get(0).exitType());
        assertEquals(360, store.rows.get(0).pnlUsd(), 1e-9);

        // later runs say how the day ended, from live_trade
        assertEquals("today's trade done: NQ short, nys-25k closed by TARGET at 21440.0 at 10:05 CT (pnl +$360)",
                service.tick(Instant.parse("2026-11-11T16:20:20Z")));
    }

    @Test
    void restart_doesNotRepeatTheEntry_butStillSendsTheExit() {
        trade = trade(21680, ExitType.OPEN, null);
        service.tick(Instant.parse("2026-11-11T15:06:20Z"));
        LiveAlertService restarted = newService();                   // same live_trade table, fresh process
        restarted.tick(Instant.parse("2026-11-11T15:07:20Z"));
        assertEquals(1, sent.size());

        trade = trade(21680, ExitType.STOP, -1.0);
        restarted.tick(Instant.parse("2026-11-11T15:40:20Z"));
        assertEquals(2, sent.size());
        assertTrue(sent.get(1).startsWith("🔴 STOP LOSS HIT") && sent.get(1).contains("Result: LOSS, you lose $120 with 1 MNQ (-60.00 pts, -1.0R)"),
                sent.get(1));
        assertTrue(restarted.tick(Instant.parse("2026-11-11T15:45:20Z")).endsWith("closed by STOP at 21680.0 at 10:05 CT (pnl -$120)"));
    }

    @Test
    void twoRunningInstances_sendEachMessageOnce() {
        LiveAlertService second = newService();
        trade = trade(21680, ExitType.OPEN, null);
        service.tick(Instant.parse("2026-11-11T15:06:20Z"));
        second.tick(Instant.parse("2026-11-11T15:06:21Z"));
        assertEquals(1, sent.size());

        trade = trade(21680, ExitType.TARGET, 3.0);
        second.tick(Instant.parse("2026-11-11T16:10:20Z"));
        service.tick(Instant.parse("2026-11-11T16:10:21Z"));
        assertEquals(2, sent.size());
    }

    @Test
    void twoAccounts_twoEntries_eachWithItsOwnRisk_andTwoExits() {
        accounts = List.of(NYS, FRESH);
        trade = trade(21680, ExitType.OPEN, null);                   // 60 points = $120 per MNQ
        service.tick(Instant.parse("2026-11-11T15:06:20Z"));
        assertEquals(2, sent.size());
        assertTrue(sent.get(0).contains("NYS · The Thunder 25k") && sent.get(0).contains("Size: 1 MNQ"), sent.get(0));
        assertTrue(sent.get(1).contains("Otra · 50k") && sent.get(1).contains("Size: 3 MNQ")
                && sent.get(1).contains("you lose $360") && sent.get(1).contains("you make $1,080"), sent.get(1));

        trade = trade(21680, ExitType.STOP, -1.0);
        service.tick(Instant.parse("2026-11-11T15:30:20Z"));
        assertEquals(4, sent.size());
        assertTrue(sent.get(2).startsWith("🔴 STOP LOSS HIT") && sent.get(2).contains("you lose $120"), sent.get(2));
        assertTrue(sent.get(3).contains("Otra") && sent.get(3).contains("you lose $360"), sent.get(3));
    }

    @Test
    void oneAccountTooTight_isSkippedAndRecorded_theOtherTrades_andOnlyItGetsTheExit() {
        accounts = List.of(NYS, FRESH);
        trade = trade(21698, ExitType.OPEN, null);                   // 78 points = $156 per MNQ: > $127, ≤ $400
        service.tick(Instant.parse("2026-11-11T15:06:20Z"));
        assertTrue(sent.get(0).startsWith("DO NOT TRADE NQ today — NYS") && sent.get(0).contains("$127"), sent.get(0));
        assertTrue(sent.get(1).contains("Otra") && sent.get(1).contains("Size: 2 MNQ"), sent.get(1));
        assertEquals(LiveTradeRecord.SKIPPED, store.rows.get(0).status());

        trade = trade(21698, ExitType.TARGET, 3.0);
        service.tick(Instant.parse("2026-11-11T16:10:20Z"));
        service.tick(Instant.parse("2026-11-11T16:11:20Z"));
        assertEquals(4, sent.size());                                // each account hears how it ended, once
        assertEquals("SKIPPED SIGNAL ENDED · SELL NQ — NYS · The Thunder 25k\nTAKE PROFIT HIT at 21,386.00, 10:05 CT: "
                + "1 MNQ would have made $468 (+234.00 pts, +3.0R). Nothing to do.", sent.get(2));
        assertTrue(sent.get(3).startsWith("✅ TAKE PROFIT HIT · SELL NQ — Otra") && sent.get(3).contains("WIN, you make $936"), sent.get(3));
        assertEquals(LiveTradeRecord.SKIPPED, store.rows.get(0).status());
        assertEquals(null, store.rows.get(0).pnlUsd());

        assertEquals("today's trade done: NQ short, nys-25k skipped (1 micro risks more than the account's max risk per "
                        + "trade), signal ended by TARGET at 21386.0 at 10:05 CT; other-50k closed by TARGET at 21386.0 at 10:05 CT (pnl +$936)",
                service.tick(Instant.parse("2026-11-11T16:20:20Z")));
    }

    @Test
    void accountsUnavailable_warnsOnce_thenSendsTheSignalWhenBack() {
        accountsBroken = true;
        trade = trade(21680, ExitType.OPEN, null);
        service.tick(Instant.parse("2026-11-11T15:06:20Z"));
        service.tick(Instant.parse("2026-11-11T15:07:20Z"));
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("Could not read the funded accounts"), sent.get(0));

        accountsBroken = false;                                      // back within the stale window
        service.tick(Instant.parse("2026-11-11T15:08:20Z"));
        assertEquals(2, sent.size());
        assertTrue(sent.get(1).contains("SELL NQ"), sent.get(1));
    }

    @Test
    void noEnabledAccounts_warnsInsteadOfSizingBlind() {
        accounts = List.of();
        trade = trade(21680, ExitType.OPEN, null);
        service.tick(Instant.parse("2026-11-11T15:06:20Z"));
        service.tick(Instant.parse("2026-11-11T15:07:20Z"));
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("No enabled funded accounts"), sent.get(0));
        assertEquals(List.of(), store.rows);
    }

    @Test
    void onlyClosedCandles_areEvaluated() {
        service.tick(Instant.parse("2026-11-11T15:07:20Z"));         // 09:07:20 CT → candles up to 09:05
        assertEquals(OffsetDateTime.parse("2026-11-11T15:05:00Z"), backtestTo.get(0));
    }

    @Test
    void incompleteData_waitsForTheNextMinute() {
        trade = trade(21680, ExitType.OPEN, null);
        dataComplete = false;
        service.tick(Instant.parse("2026-11-11T15:06:20Z"));
        assertEquals(List.of(), sent);
        assertEquals(List.of(), backtestTo);
    }

    @Test
    void tooRiskyForOneMicro_isAnnouncedAsSkipped_once() {
        trade = trade(21795, ExitType.OPEN, null);                   // 175 points: 1 MNQ = $350 > $127
        service.tick(Instant.parse("2026-11-11T15:06:20Z"));
        assertTrue(service.tick(Instant.parse("2026-11-11T15:07:20Z")).endsWith("no open trade; still open, last 21620.0 (+0.00 pts)"));
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("DO NOT TRADE"), sent.get(0));

        trade = trade(21795, ExitType.STOP, -1.0);                   // the skipped signal still gets its ending, once
        service.tick(Instant.parse("2026-11-11T15:40:20Z"));
        service.tick(Instant.parse("2026-11-11T15:41:20Z"));
        assertEquals(2, sent.size());
        assertEquals("SKIPPED SIGNAL ENDED · SELL NQ — NYS · The Thunder 25k\nSTOP LOSS HIT at 21,795.00, 10:05 CT: "
                + "1 MNQ would have lost $350 (-175.00 pts, -1.0R). Nothing to do.", sent.get(1));
        assertEquals("today's trade done: NQ short, nys-25k skipped (1 micro risks more than the account's max risk per trade), "
                + "signal ended by STOP at 21795.0 at 10:05 CT", service.tick(Instant.parse("2026-11-11T15:45:20Z")));
    }

    @Test
    void pickFirstSeenTooLate_isNotAnnounced() {
        trade = trade(21680, ExitType.OPEN, null);
        service.tick(Instant.parse("2026-11-11T15:30:20Z"));         // 25 minutes after the entry (e.g. a restart)
        assertEquals(List.of(), sent);
        assertEquals(List.of(), store.rows);
    }

    @Test
    void stillOpenAt1455_getsOneReminder() {
        trade = trade(21680, ExitType.OPEN, null);
        service.tick(Instant.parse("2026-11-11T15:06:20Z"));
        service.tick(Instant.parse("2026-11-11T20:55:20Z"));         // 14:55 CT
        service.tick(Instant.parse("2026-11-11T20:56:20Z"));
        assertEquals(2, sent.size());
        assertEquals("Close the SELL on NQ before 3:00 PM CT on: NYS · The Thunder 25k", sent.get(1));
    }

    @Test
    void stillOpenAtTheBell_isClosedOnceThe1500CandleIsIn() {
        trade = trade(21680, ExitType.OPEN, null);
        service.tick(Instant.parse("2026-11-11T15:06:20Z"));
        service.tick(Instant.parse("2026-11-11T21:04:20Z"));         // 15:04 CT: candles up to 15:00, still open
        assertEquals(LiveTradeRecord.OPEN, store.rows.get(0).status());

        trade = trade(21680, ExitType.CLOSE, -0.5);                  // the backtest flattens on the 15:00 candle
        service.tick(Instant.parse("2026-11-11T21:05:20Z"));         // the minute loop runs at :20 past 15:05
        assertEquals(Instant.parse("2026-11-11T21:05:00Z"), backtestTo.get(backtestTo.size() - 1).toInstant());
        assertEquals(LiveTradeRecord.CLOSED, store.rows.get(0).status());
        assertEquals("session_close", store.rows.get(0).exitType());
        assertTrue(sent.get(sent.size() - 1).startsWith("CLOSED AT 3:00 PM CT"), sent.get(sent.size() - 1));
        assertTrue(sent.get(sent.size() - 1).contains("Result: LOSS, you lose $120 with 1 MNQ (-60.00 pts, -1.0R)"), sent.get(sent.size() - 1));
    }

    @Test
    void dollarsComeFromTheCurrentPrice_andALateEntryIsFlagged() {
        trade = trade(21680, ExitType.OPEN, null);                   // short at 21,620, stop 21,680, target 21,440
        lastPrice = 21580;                                           // already 40 points (> 0.5R) in favor
        service.tick(Instant.parse("2026-11-11T15:06:20Z"));
        String m = sent.get(0);
        assertTrue(m.contains("Entry: market, now (≈ 21,580.00)"), m);
        assertTrue(m.contains("you lose $200"), m);                  // 100 points to the stop from here
        assertTrue(m.contains("you make $280"), m);                  // 140 points to the target from here
        assertTrue(m.contains("Heads up: price already moved $80 in your favor"), m);
        assertEquals(21580, store.rows.get(0).entryEstimate());
    }

    @Test
    void marketDataStops_inSession_warnsOncePerOutage() {
        service.tick(Instant.parse("2026-11-11T15:00:20Z"));         // data flowing, no pick yet
        dataComplete = false;
        service.tick(Instant.parse("2026-11-11T15:02:20Z"));
        service.tick(Instant.parse("2026-11-11T15:05:20Z"));         // 5 minutes: not yet
        assertEquals(List.of(), sent);
        service.tick(Instant.parse("2026-11-11T15:06:20Z"));
        service.tick(Instant.parse("2026-11-11T15:07:20Z"));
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).startsWith("No market data for the last 6 minutes"), sent.get(0));

        dataComplete = true;                                         // back: the signal still goes out in time
        trade = trade(21680, ExitType.OPEN, null);
        service.tick(Instant.parse("2026-11-11T15:08:20Z"));
        assertEquals(2, sent.size());
        assertTrue(sent.get(1).contains("SELL NQ"), sent.get(1));
    }

    @Test
    void sessionWindow() {
        assertTrue(LiveAlertService.inSession(Instant.parse("2026-11-11T14:31:00Z")));      // 08:31 CT
        assertTrue(LiveAlertService.inSession(Instant.parse("2026-11-11T21:10:00Z")));      // 15:10 CT
        assertTrue(!LiveAlertService.inSession(Instant.parse("2026-11-11T21:11:00Z")));
        assertTrue(!LiveAlertService.inSession(Instant.parse("2026-11-14T16:00:00Z")));     // Saturday
    }

    @Test
    void outsideTheSession_doesNothing() {
        trade = trade(21680, ExitType.OPEN, null);
        service.tick(Instant.parse("2026-11-11T13:00:20Z"));         // 07:00 CT
        service.tick(Instant.parse("2026-11-14T15:06:20Z"));         // Saturday
        assertEquals(List.of(), backtestTo);
    }
}
