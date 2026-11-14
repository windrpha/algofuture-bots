package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.ContractSpec;
import com.algofuture.bots.liquiditysweep.domain.model.ExitType;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDirection;
import com.algofuture.bots.liquiditysweep.domain.model.SweepStatus;
import com.algofuture.bots.liquiditysweep.domain.model.SweepTrade;
import com.algofuture.bots.liquiditysweep.domain.model.TradeResult;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks every CONFIRMED sweep forward through the candles until stop, target or time exit.
 *
 * <ul>
 *   <li>One position at a time: a sweep confirming while a trade is still on is skipped.</li>
 *   <li>Entry fills at the confirmation candle's close; 1 contract.</li>
 *   <li>If a single candle touches both stop and target, the STOP is assumed first (the candle's
 *       intrabar path is unknown, so the conservative outcome is taken).</li>
 *   <li>With {@code flatAtSessionClose}, a trade still on at the session end exits at the open of the first
 *       candle after it; on an early-close day (no candle left that day) it exits at the last candle's close.</li>
 *   <li>If the data ends before any exit the trade stays OPEN (no exit, no P&amp;L).</li>
 * </ul>
 */
public final class SweepTradeSimulator {

    private final SweepDetectorConfig config;
    private final ContractSpec contract;
    private final Duration barDuration;

    public SweepTradeSimulator(SweepDetectorConfig config, ContractSpec contract, Duration barDuration) {
        this.config = config;
        this.contract = contract;
        this.barDuration = barDuration;
    }

    public List<SweepTrade> simulate(List<TradesBar1m> bars, List<LiquiditySweep> sweeps) {
        Map<Instant, Integer> indexByTime = new HashMap<>();
        for (int i = 0; i < bars.size(); i++) {
            indexByTime.put(bars.get(i).tsEvent(), i);
        }

        List<LiquiditySweep> confirmed = sweeps.stream()
                .filter(s -> s.status() == SweepStatus.CONFIRMED)
                .sorted(Comparator.comparing(LiquiditySweep::confirmedAt))
                .toList();

        List<SweepTrade> trades = new ArrayList<>();
        Instant flatFrom = Instant.MIN;                 // first candle on which a new entry is allowed
        for (LiquiditySweep sweep : confirmed) {
            if (sweep.confirmedAt().isBefore(flatFrom)) continue;

            SweepTrade trade = walk(bars, indexByTime.get(sweep.confirmedAt()), sweep);
            trades.add(trade);
            if (trade.result() == TradeResult.OPEN) break; // still in the market until the data ends
            flatFrom = trade.exitAt();
        }
        return trades;
    }

    private SweepTrade walk(List<TradesBar1m> bars, int entryIdx, LiquiditySweep sweep) {
        boolean isLong = sweep.direction() == SweepDirection.BULLISH;
        double entry = sweep.entry(), stop = sweep.stop(), target = sweep.target();

        Instant exitAt = null;
        Double exitPrice = null;
        ExitType exitType = ExitType.OPEN;

        LocalDate entryDay = config.dayOf(bars.get(entryIdx).tsEvent());
        Instant sessionEnd = config.sessionEnd(entryDay);
        for (int k = entryIdx + 1; k < bars.size(); k++) {
            TradesBar1m b = bars.get(k);
            if (config.flatAtSessionClose() && config.dayOf(b.tsEvent()).isAfter(entryDay)) {
                // early close (holiday): no candle of the entry day reached the session end, so flatten
                // at the last one instead of carrying the trade to the next session's open
                TradesBar1m last = bars.get(k - 1);
                exitAt = last.tsEvent().plus(barDuration); exitPrice = last.close(); exitType = ExitType.CLOSE;
                break;
            }
            if (config.flatAtSessionClose() && !b.tsEvent().isBefore(sessionEnd)) {
                exitAt = b.tsEvent(); exitPrice = b.open(); exitType = ExitType.CLOSE;
                break;
            }
            boolean stopHit = isLong ? b.low() <= stop : b.high() >= stop;
            boolean targetHit = isLong ? b.high() >= target : b.low() <= target;
            if (stopHit) {
                exitAt = b.tsEvent(); exitPrice = stop; exitType = ExitType.STOP;
                break;
            }
            if (targetHit) {
                exitAt = b.tsEvent(); exitPrice = target; exitType = ExitType.TARGET;
                break;
            }
            if (config.maxHoldBars() > 0 && k - entryIdx >= config.maxHoldBars()) {
                exitAt = b.tsEvent().plus(barDuration); exitPrice = b.close(); exitType = ExitType.TIME;
                break;
            }
        }

        double risk = Math.abs(entry - stop);
        double rrPlanned = Math.abs(target - entry) / risk;
        Instant entryAt = bars.get(entryIdx).tsEvent().plus(barDuration);

        if (exitPrice == null) {
            return new SweepTrade(sweep, entryAt, entry, stop, target, null, null, ExitType.OPEN,
                    TradeResult.OPEN, rrPlanned, null, null, null);
        }

        double points = (exitPrice - entry) * (isLong ? 1 : -1);
        int ticks = (int) Math.round(points / contract.tickSize());
        TradeResult result = ticks > 0 ? TradeResult.WIN : ticks < 0 ? TradeResult.LOSS : TradeResult.BREAKEVEN;
        return new SweepTrade(sweep, entryAt, entry, stop, target, exitAt, exitPrice, exitType, result,
                rrPlanned, points / risk, ticks, points * contract.pointValue());
    }
}
