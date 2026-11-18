package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.DailyPick;
import com.algofuture.bots.liquiditysweep.domain.model.ExitType;
import com.algofuture.bots.liquiditysweep.domain.model.FundedAccount;
import com.algofuture.bots.liquiditysweep.domain.model.SweepTrade;
import com.algofuture.bots.liquiditysweep.domain.service.ConvictionSelector.Reason;
import com.algofuture.bots.liquiditysweep.domain.service.MicroSizing.Size;
import com.algofuture.shared.journal.domain.model.LiveTradeRecord;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Plain-text alert messages (English) for a trader executing by hand: what to do and how much is at stake come first
 * (account, side, micros, stop loss and take profit in price AND dollars); the context goes last. Only two emojis, so
 * they always mean the same thing: ✅ take profit hit, 🔴 stop loss hit.
 */
public final class AlertMessages {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final Map<Reason, String> REASONS = Map.of(
            Reason.KEY_LEVEL, "key level", Reason.CORRELATED, "other index confirmed",
            Reason.WITH_GAP, "with the gap", Reason.THIN_EXTREME, "thin extreme");

    private AlertMessages() {
    }

    /**
     * One message per account.
     *
     * @param lastPrice latest 1m close, so the trader sees how far price already moved from the signal
     */
    public static String entry(String strategy, DailyPick pick, FundedAccount account, Size size, double lastPrice) {
        SweepTrade t = pick.trade();
        // dollars from where the trader actually gets in (the latest price), not from the signal close
        double riskPoints = Math.abs(lastPrice - t.stopLoss());
        double rewardPoints = Math.abs(t.takeProfit() - lastPrice);
        double moved = t.isLong() ? lastPrice - t.entryPrice() : t.entryPrice() - lastPrice;
        String warning = moved > 0.5 * Math.abs(t.entryPrice() - t.stopLoss())
                ? "\nHeads up: price already moved %s in your favor since the signal; entering now means less reward and more risk."
                        .formatted(money(size.usd(moved)))
                : "";
        return """
                %s %s%s%s
                Size: %d %s (%s)
                Entry: market, now (≈ %s)
                Stop loss: %s → you lose %s
                Take profit: %s → you make %s
                If neither is hit: close at 3:00 PM CT%s

                %sSignal %s CT · %s · %s""".formatted(
                t.isLong() ? "BUY" : "SELL", pick.contract().root(), dash(account), account(account),
                size.contracts(), size.micro(), size.description(),
                price(lastPrice),
                price(t.stopLoss()), money(size.usd(riskPoints)),
                price(t.takeProfit()), money(size.usd(rewardPoints)),
                warning,
                roomLine(account, size.usd(riskPoints)), HHMM.format(t.entryAt().atZone(CHICAGO)), strategy,
                pick.reasons().stream().map(REASONS::get).collect(Collectors.joining(", ")));
    }

    public static String skipped(String strategy, DailyPick pick, FundedAccount account, Size single) {
        return """
                DO NOT TRADE %s today%s%s
                1 %s would risk %s; this account's max risk per trade is %s.
                %s""".formatted(pick.contract().root(), dash(account), account(account),
                single.micro(), money(single.riskUsd()), money(account.maxRisk()), strategy).strip();
    }

    /** Outcome for one account, from its {@code live_trade} row once closed at {@code exitPrice}. */
    public static String exit(LiveTradeRecord trade, ExitType exitType, double exitPrice, Instant exitAt, double pnlUsd) {
        double points = trade.isLong() ? exitPrice - trade.entryEstimate() : trade.entryEstimate() - exitPrice;
        String verdict = Math.abs(pnlUsd) < 0.5 ? "BREAKEVEN, $0"
                : pnlUsd < 0 ? "LOSS, you lose " + money(-pnlUsd) : "WIN, you make " + money(pnlUsd);
        return """
                %s · %s %s — %s
                Result: %s with %d %s (%+.2f pts, %+.1fR)
                Entry ≈ %s · exit %s at %s CT
                Update max_loss_used for this account in funded_account with what the dashboard shows.""".formatted(
                how(exitType), side(trade), trade.symbol(), trade.accountLabel(),
                verdict, trade.contracts(), trade.micro(), points, points / risk(trade),
                price(trade.entryEstimate()), price(exitPrice), HHMM.format(exitAt.atZone(CHICAGO)));
    }

    /** How a signal the account did not take ended, so the trader knows what skipping it meant. */
    public static String skippedExit(LiveTradeRecord trade, ExitType exitType, double exitPrice, Instant exitAt) {
        double points = trade.isLong() ? exitPrice - trade.signalPrice() : trade.signalPrice() - exitPrice;
        double usd = points * MicroSizing.usdPerPoint(trade.symbol(), 1);
        return """
                SKIPPED SIGNAL ENDED · %s %s — %s
                %s at %s, %s CT: 1 %s would have %s %s (%+.2f pts, %+.1fR). Nothing to do.""".formatted(
                side(trade), trade.symbol(), trade.accountLabel(),
                how(exitType).replaceFirst("^[✅🔴] ", ""), price(exitPrice), HHMM.format(exitAt.atZone(CHICAGO)),
                MicroSizing.microOf(trade.symbol()), usd < 0 ? "lost" : "made", money(Math.abs(usd)), points,
                points / risk(trade));
    }

    private static String how(ExitType exitType) {
        return switch (exitType) {
            case STOP -> "🔴 STOP LOSS HIT";
            case TARGET -> "✅ TAKE PROFIT HIT";
            case CLOSE -> "CLOSED AT 3:00 PM CT";
            case TIME -> "TIME EXIT";
            case OPEN -> "OPEN";
        };
    }

    private static String side(LiveTradeRecord trade) {
        return trade.isLong() ? "BUY" : "SELL";
    }

    /** Points from the signal price to the stop loss: 1R. */
    private static double risk(LiveTradeRecord trade) {
        return Math.abs(trade.signalPrice() - trade.stopLoss());
    }

    public static String closeReminder(String symbol, boolean isLong, List<String> accountLabels) {
        return "Close the %s on %s before 3:00 PM CT on: %s".formatted(isLong ? "BUY" : "SELL", symbol,
                String.join(", ", accountLabels));
    }

    public static String noMarketData(long minutes) {
        return "No market data for the last %d minutes (trade_1m_live is not updating): no signals until it is back."
                .formatted(minutes);
    }

    public static String noAccounts() {
        return "No enabled funded accounts in the funded_account table: today's signal is not sent.";
    }

    public static String accountsProblem(String detail) {
        return "Could not read the funded accounts: no signals will be sent until it is fixed. " + detail;
    }

    private static String dash(FundedAccount account) {
        return " — ";
    }

    private static String account(FundedAccount account) {
        return account.label();
    }

    /** "Risk: 14% of the $637 left on this account" when the account tracks its max loss. */
    private static String roomLine(FundedAccount account, double riskUsd) {
        if (account.maxLoss() <= 0 || account.room() <= 0) return "";
        return "Risk: %.0f%% of the %s left on this account%n".formatted(100 * riskUsd / account.room(), money(account.room()));
    }

    private static String price(Double p) {
        return p == null ? "-" : String.format(Locale.US, "%,.2f", p);
    }

    private static String money(double v) {
        return String.format(Locale.US, "$%,.0f", v);
    }
}
