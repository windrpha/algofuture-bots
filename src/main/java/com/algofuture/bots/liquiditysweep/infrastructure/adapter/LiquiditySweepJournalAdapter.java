package com.algofuture.bots.liquiditysweep.infrastructure.adapter;

import com.algofuture.bots.liquiditysweep.application.port.out.TradeJournalPort;
import com.algofuture.bots.liquiditysweep.domain.model.ContractSpec;
import com.algofuture.bots.liquiditysweep.domain.model.ConvictionConfig;
import com.algofuture.bots.liquiditysweep.domain.model.DailyBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SessionBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.SessionConfig;
import com.algofuture.bots.liquiditysweep.domain.model.SweepBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.bots.liquiditysweep.domain.model.SweepTrade;
import com.algofuture.bots.liquiditysweep.domain.service.StopWidth;
import com.algofuture.shared.infrastructure.persistence.StrategyTradeRepository;
import com.algofuture.shared.journal.domain.model.StrategyTrade;
import com.algofuture.shared.journal.domain.model.StrategyTrade.UpsertOutcome;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps sweep trades onto {@code strategy_trade} rows, following the conventions of the existing
 * journal: contract root as symbol ("NQ"), {@code long}/{@code short}, and backtest provenance
 * (run_id, point_value, source_symbol, ET candle starts) in {@code metadata}.
 */
@ApplicationScoped
public class LiquiditySweepJournalAdapter implements TradeJournalPort {

    private static final String DESCRIPTION = "Liquidity sweep: a candle trades beyond a resting high/low, "
            + "closes back inside, and the next candles close beyond the sweep candle's opposite extreme.";
    private static final String HOUR_LEVELS = " Liquidity also includes overnight 1h highs/lows untouched at the open.";
    private static final String SESSION_DESCRIPTION = "Liquidity sweep, one trade per session on %s: the first "
            + "failed-breakout sweep between %s and %s that the correlated index confirms by sweeping its own level.";
    private static final String OPENING_RANGE = " Liquidity also includes the high/low of the first %d minutes, once that range has closed.";
    private static final String RANGE_TARGET = " Take profit at the opposite side of that range when it is at least 1R away, otherwise at the fixed R target.";
    private static final String DAILY_DESCRIPTION = "Liquidity sweep, one trade per day on %s: the first "
            + "failed-breakout sweep between %s and %s of a key level (overnight session/day/week, overnight 1h, "
            + "first %d minutes) or an intraday swing, with %d+ reasons before %s and %d+ after. Reasons: key level "
            + "(not a swing), the other index swept the same way in the previous %d minutes, trade in the direction "
            + "of the opening gap.%s Target %sR, flat at the close.";
    private static final String FOOTPRINT_REASON = " Footprint reason: thin extreme (the two most extreme prices of "
            + "the excursion traded <= %.2f of an average price of it).";
    private static final String STOP_BUFFER = " Stop beyond the sweep wick by %s, target at the same R of the wider risk.";
    private static final String STOP_WIDTH = " Skips sweeps whose stop at the wick is wider than %.2f times the mean range "
            + "of the %d candles before the confirmation.";
    private static final List<String> TAGS = List.of("backtest", "liquidity_sweep");
    private static final List<String> SESSION_TAGS = List.of("backtest", "liquidity_sweep", "one_per_session");
    private static final List<String> DAILY_TAGS = List.of("backtest", "liquidity_sweep", "one_per_day", "conviction");
    private static final String AUTHOR = "backtest";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final StrategyTradeRepository repository;

    public LiquiditySweepJournalAdapter(StrategyTradeRepository repository) {
        this.repository = repository;
    }

    /** What every row of one run shares. */
    private record Run(int strategyId, String runId, String interval, SweepDetectorConfig cfg,
                       Map<String, Object> extraMetadata, List<String> tags) {}

    @Override
    public SweepBacktest.JournalResult record(SweepBacktest backtest) {
        SweepDetectorConfig cfg = backtest.config();
        int strategyId = repository.ensureStrategy(cfg.strategyName(),
                cfg.hourLevels() ? DESCRIPTION + HOUR_LEVELS : DESCRIPTION);
        Run run = new Run(strategyId, backtest.runId(), backtest.interval(), cfg, Map.of(), TAGS);
        return upsert(strategyId, backtest.trades().stream().map(t -> toRow(t, backtest.contract(), run)).toList());
    }

    @Override
    public SweepBacktest.JournalResult recordSession(SessionBacktest backtest) {
        SessionConfig session = backtest.session();
        int strategyId = repository.ensureStrategy(session.strategyName(backtest.config()), SESSION_DESCRIPTION.formatted(
                String.join("/", backtest.symbols()), backtest.config().sessionStart(), session.lastEntry())
                + (backtest.config().openingRangeMinutes() > 0
                        ? OPENING_RANGE.formatted(backtest.config().openingRangeMinutes()) : "")
                + (session.rangeTarget() ? RANGE_TARGET : ""));
        Map<String, Object> extra = Map.of(
                "session_symbols", backtest.symbols(),
                "session_last_entry", session.lastEntry().toString(),
                "smt_confirmation", session.smtConfirmation(),
                "range_target", session.rangeTarget());
        Run run = new Run(strategyId, backtest.runId(), backtest.interval(), backtest.config(), extra, SESSION_TAGS);
        return upsert(strategyId, backtest.picks().stream().map(p -> toRow(p.trade(), p.contract(), run)).toList());
    }

    @Override
    public SweepBacktest.JournalResult recordDaily(DailyBacktest backtest) {
        SweepDetectorConfig cfg = backtest.config();
        ConvictionConfig conviction = backtest.conviction();
        String footprint = conviction.footprint() == null ? ""
                : FOOTPRINT_REASON.formatted(conviction.footprint().thinExtremeRatio());
        String buffers = conviction.stopBufferTicks().entrySet().stream().filter(e -> e.getValue() > 0)
                .map(e -> e.getValue() + " ticks on " + e.getKey()).sorted().collect(Collectors.joining(", "));
        int strategyId = repository.ensureStrategy(conviction.strategyName(), DAILY_DESCRIPTION.formatted(
                String.join("/", backtest.symbols()), cfg.sessionStart(), cfg.lastSweepTime(), cfg.openingRangeMinutes(),
                conviction.earlyReasons(), conviction.strictUntil(), conviction.lateReasons(),
                conviction.correlationWindow().toMinutes(), footprint + (buffers.isEmpty() ? "" : STOP_BUFFER.formatted(buffers))
                        + (conviction.maxRiskAtr() > 0 ? STOP_WIDTH.formatted(conviction.maxRiskAtr(), StopWidth.ATR_BARS) : ""),
                cfg.rewardRisk()));
        return upsert(strategyId, backtest.picks().stream().map(p -> {
            Map<String, Object> extra = Map.of(
                    "session_symbols", backtest.symbols(),
                    "conviction_reasons", p.reasons().stream().map(Enum::name).toList(),
                    "strict_until", conviction.strictUntil().toString(),
                    "swing_strength", conviction.swingStrength(),
                    "stop_buffer_ticks", conviction.stopBufferTicks(p.contract().root()),
                    "max_risk_atr", conviction.maxRiskAtr());
            return toRow(p.trade(), p.contract(),
                    new Run(strategyId, backtest.runId(), backtest.interval(), cfg, extra, DAILY_TAGS));
        }).toList());
    }

    private SweepBacktest.JournalResult upsert(int strategyId, List<StrategyTrade> rows) {
        List<UpsertOutcome> outcomes = repository.upsert(rows);
        return new SweepBacktest.JournalResult(strategyId,
                count(outcomes, UpsertOutcome.INSERTED),
                count(outcomes, UpsertOutcome.UPDATED),
                count(outcomes, UpsertOutcome.UNCHANGED));
    }

    private StrategyTrade toRow(SweepTrade t, ContractSpec c, Run run) {
        LiquiditySweep s = t.sweep();
        SweepDetectorConfig cfg = run.cfg();
        String side = t.isLong() ? "long" : "short";

        String entryReason = "Liquidity sweep %s: price swept %s (%s) by %d ticks and a %s candle closed back %s; "
                .formatted(side, String.join("+", s.levelsSwept()), s.sweptLevel(),
                        Math.round(s.penetration() / c.tickSize()), run.interval(), t.isLong() ? "above" : "below")
                + "confirmed by a close %s the reclaim candle %s".formatted(
                        t.isLong() ? "above" : "below", t.isLong() ? "high" : "low");

        String exitReason = switch (t.exitType()) {
            case STOP -> "Stop loss: price took out the sweep wick";
            case TARGET -> Math.abs(t.rrPlanned() - cfg.rewardRisk()) > 1e-6   // only the range target differs
                    ? "Take profit: opposite side of the opening range (%.2fR)".formatted(t.rrPlanned())
                    : "Take profit: %sR target".formatted(cfg.rewardRisk());
            case TIME -> "Time exit: %d %s candles in the trade".formatted(cfg.maxHoldBars(), run.interval());
            case CLOSE -> "Session close: flat at %s".formatted(cfg.sessionEnd());
            case OPEN -> null;
        };

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("run_id", run.runId());
        metadata.put("backtest", true);
        metadata.put("exit_type", t.exitType().code);
        metadata.put("timeframe", run.interval());
        metadata.put("point_value", c.pointValue());
        metadata.put("source_symbol", c.sourceSymbol());
        metadata.put("swept_level", s.sweptLevel());
        metadata.put("swept_level_type", s.levelType().label);
        metadata.put("levels_swept", s.levelsSwept());
        metadata.put("sweep_extreme", s.sweepExtreme());
        metadata.put("penetration_ticks", Math.round(s.penetration() / c.tickSize()));
        metadata.put("level_candle_start_et", et(s.levelAt()));
        metadata.put("pierce_candle_start_et", et(s.piercedAt()));
        metadata.put("sweep_candle_start_et", et(s.sweepAt()));
        metadata.put("confirmation_candle_start_et", et(s.confirmedAt()));
        metadata.put("config", Map.ofEntries(
                Map.entry("session", "%s-%s %s".formatted(cfg.sessionStart(), cfg.sessionEnd(), cfg.zone())),
                Map.entry("min_penetration_ticks", cfg.minPenetrationTicks()),
                Map.entry("min_excursion_bars", cfg.minExcursionBars()),
                Map.entry("reclaim_bars", cfg.reclaimBars()),
                Map.entry("confirmation_bars", cfg.confirmationBars()),
                Map.entry("reward_risk", cfg.rewardRisk()),
                Map.entry("max_hold_bars", cfg.maxHoldBars()),
                Map.entry("last_sweep_time", String.valueOf(cfg.lastSweepTime())),
                Map.entry("flat_at_session_close", cfg.flatAtSessionClose()),
                Map.entry("hour_levels", cfg.hourLevels()),
                Map.entry("opening_range_minutes", cfg.openingRangeMinutes())));
        metadata.putAll(run.extraMetadata());

        return new StrategyTrade(
                c.root(), run.strategyId(), side,
                t.entryAt(), t.entryPrice(), entryReason,
                t.exitAt(), t.exitPrice(), exitReason,
                t.stopLoss(), t.takeProfit(), BigDecimal.ONE,
                rr(t.rrPlanned()), t.rrRealized() == null ? null : rr(t.rrRealized()),
                t.result().dbValue(), t.pnl() == null ? null : cents(t.pnl()), t.pnlTicks(),
                metadata, run.tags(), AUTHOR);
    }

    /** Money to the cent: prices like RTY's 0.1 ticks leave float residue (1310.00000000001). */
    private static double cents(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** rr_* columns are NUMERIC(6,2). */
    private static BigDecimal rr(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String et(java.time.Instant instant) {
        return instant.atZone(NEW_YORK).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).toString();
    }

    private static int count(List<UpsertOutcome> outcomes, UpsertOutcome kind) {
        return (int) outcomes.stream().filter(o -> o == kind).count();
    }
}
