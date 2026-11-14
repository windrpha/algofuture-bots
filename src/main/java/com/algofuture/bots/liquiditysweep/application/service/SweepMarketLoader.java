package com.algofuture.bots.liquiditysweep.application.service;

import com.algofuture.bots.liquiditysweep.application.port.out.LiquiditySweepBarDataPort;
import com.algofuture.bots.liquiditysweep.domain.model.ContractSpec;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.bots.liquiditysweep.domain.service.KeyLevelCalculator;
import com.algofuture.bots.liquiditysweep.domain.service.LiquiditySweepDetector;
import com.algofuture.bots.liquiditysweep.domain.service.SwingLevels;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Loads one contract's candles and levels and detects its sweeps: the shared first step of every strategy. */
@ApplicationScoped
public class SweepMarketLoader {

    private static final int LEVEL_HISTORY_DAYS = 16;

    /**
     * @param rawLevels levels including those crossed before the open; only filled when requested
     *                  (another contract's SMT check needs them)
     */
    public record SweepMarket(ContractSpec contract, List<TradesBar1m> bars, Map<LocalDate, List<KeyLevel>> levels,
                              Map<LocalDate, List<KeyLevel>> rawLevels, List<LiquiditySweep> sweeps) {}

    private final LiquiditySweepBarDataPort barData;

    public SweepMarketLoader(LiquiditySweepBarDataPort barData) {
        this.barData = barData;
    }

    public SweepMarket load(String symbol, OffsetDateTime from, OffsetDateTime to, String interval,
                            SweepDetectorConfig config, boolean withRawLevels) {
        return load(symbol, from, to, interval, config, withRawLevels, 0);
    }

    /** @param swingStrength {@code > 0} also trades intraday swing extremes ({@link SwingLevels}, strategy 4.0) */
    public SweepMarket load(String symbol, OffsetDateTime from, OffsetDateTime to, String interval,
                            SweepDetectorConfig config, boolean withRawLevels, int swingStrength) {
        Duration barDuration = parseInterval(interval);
        ContractSpec contract = ContractSpec.of(symbol, barData.findTickSize(symbol).orElse(null));
        List<TradesBar1m> bars = barData.findBars(symbol, from, to, interval);
        // Key levels need 1m precision and history back to the previous week (+ slack for holidays)
        List<TradesBar1m> m1 = barData.findBars(symbol, from.minusDays(LEVEL_HISTORY_DAYS), to, "1m");
        List<LocalDate> days = tradingDays(bars, config);
        KeyLevelCalculator calculator = new KeyLevelCalculator(config);
        Map<LocalDate, List<KeyLevel>> levels = calculator.levelsByDay(m1, days);
        if (swingStrength > 0) levels = SwingLevels.with(levels, bars, config, barDuration, swingStrength);
        Map<LocalDate, List<KeyLevel>> raw = withRawLevels ? calculator.rawLevelsByDay(m1, days) : Map.of();
        List<LiquiditySweep> sweeps = new LiquiditySweepDetector(config, contract.tickSize()).detect(symbol, bars, levels);
        return new SweepMarket(contract, bars, levels, raw, sweeps);
    }

    /** Days with at least one candle inside the trading window. */
    private static List<LocalDate> tradingDays(List<TradesBar1m> bars, SweepDetectorConfig config) {
        return bars.stream()
                .map(TradesBar1m::tsEvent)
                .filter(config::inSession)
                .map(config::dayOf)
                .distinct()
                .toList();
    }

    /** "5m" / "1h" / "1d" / "1w" → Duration (same units the bar query accepts). */
    public static Duration parseInterval(String interval) {
        if (interval == null || !interval.matches("\\d+[mhdw]")) {
            throw new IllegalArgumentException("Invalid interval: " + interval + " (expected e.g. 5m, 1h, 1d)");
        }
        long amount = Long.parseLong(interval.substring(0, interval.length() - 1));
        return switch (interval.charAt(interval.length() - 1)) {
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> Duration.ofDays(7 * amount);
        };
    }
}
