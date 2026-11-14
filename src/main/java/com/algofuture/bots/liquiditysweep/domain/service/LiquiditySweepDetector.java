package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDirection;
import com.algofuture.bots.liquiditysweep.domain.model.SweepStatus;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Pure liquidity-sweep detection over a candle series (no I/O, no framework).
 *
 * <h2>Rules (bullish; bearish is the exact mirror)</h2>
 * <ol>
 *   <li><b>Liquidity</b>: only key levels — overnight session low (ONL), previous day low (PDL),
 *       previous week low (PWL) — still untouched at the session start ({@link KeyLevelCalculator}).
 *       A level only counts from its {@code activeFrom} on (the opening range once it has closed).</li>
 *   <li><b>Window</b>: only candles inside the trading window (default 08:30–15:00 Chicago) can sweep
 *       or confirm.</li>
 *   <li><b>Pierce</b>: a candle trades STRICTLY below a level, which consumes it for the rest of the day.</li>
 *   <li><b>Reclaim (sweep candle)</b>: within {@code reclaimBars} candles counting the pierce candle
 *       (1 = the pierce candle itself must close back), a candle CLOSES back above the level, and not
 *       before {@code minExcursionBars} (2 = the pierce candle closed below: a failed breakdown) nor at or
 *       after {@code lastSweepTime}. The
 *       excursion's lowest low must be at least {@code minPenetrationTicks} beyond it. No close back
 *       in time is a breakdown: the level is gone and there is no signal. If several levels are
 *       reclaimed on the same candle, the deepest one is reported.</li>
 *   <li><b>Confirmation</b>: within {@code confirmationBars} (and the window), a candle closes above
 *       the reclaim candle's HIGH. If price prints below the excursion low first, or the window
 *       runs out, the sweep is INVALIDATED.</li>
 *   <li><b>Trade</b>: entry = confirmation close, stop = excursion low,
 *       target = entry + {@code rewardRisk} × risk.</li>
 * </ol>
 *
 * The bearish side runs the same code on negated prices (high/low swapped), so both directions
 * share one implementation.
 */
public final class LiquiditySweepDetector {

    private final SweepDetectorConfig config;
    private final double minPenetration;

    /** @param tickSize from {@code instrument.tick_size}; turns {@code minPenetrationTicks} into price. */
    public LiquiditySweepDetector(SweepDetectorConfig config, double tickSize) {
        this.config = config;
        // half a tick of slack absorbs floating-point noise in prices like 27047.75
        this.minPenetration = config.minPenetrationTicks() * tickSize - tickSize / 2;
    }

    /** {@code bars} ascending by time; {@code levelsByDay} from {@link KeyLevelCalculator}. */
    public List<LiquiditySweep> detect(String symbol, List<TradesBar1m> bars, Map<LocalDate, List<KeyLevel>> levelsByDay) {
        List<LiquiditySweep> sweeps = new ArrayList<>();
        sweeps.addAll(detectSide(symbol, bars, levelsByDay, SweepDirection.BULLISH));
        sweeps.addAll(detectSide(symbol, bars, levelsByDay, SweepDirection.BEARISH));
        sweeps.sort(Comparator.comparing(LiquiditySweep::sweepAt).thenComparing(LiquiditySweep::direction));
        return sweeps;
    }

    private List<LiquiditySweep> detectSide(String symbol, List<TradesBar1m> bars,
                                            Map<LocalDate, List<KeyLevel>> levelsByDay, SweepDirection direction) {
        // In "bullish space" we always look for a swept LOW. For the bearish side prices are negated,
        // so the original high becomes the mirrored low.
        int s = direction == SweepDirection.BULLISH ? 1 : -1;
        KeyLevel.Kind side = s > 0 ? KeyLevel.Kind.LOW : KeyLevel.Kind.HIGH;
        int n = bars.size();
        double[] low = new double[n], high = new double[n], close = new double[n];
        boolean[] inSession = new boolean[n];
        for (int i = 0; i < n; i++) {
            TradesBar1m b = bars.get(i);
            low[i] = s > 0 ? b.low() : -b.high();
            high[i] = s > 0 ? b.high() : -b.low();
            close[i] = s * b.close();
            inSession[i] = config.inSession(b.tsEvent());
        }

        List<LiquiditySweep> out = new ArrayList<>();
        LocalDate currentDay = null;
        List<KeyLevel> active = new ArrayList<>();
        List<Excursion> pending = new ArrayList<>();   // levels traded through, waiting for a close back
        for (int i = 0; i < n; i++) {
            if (!inSession[i]) continue;
            LocalDate day = config.dayOf(bars.get(i).tsEvent());
            if (!day.equals(currentDay)) {
                currentDay = day;
                pending.clear();                       // an excursion never survives the session close
                active = new ArrayList<>(levelsByDay.getOrDefault(day, List.of()).stream()
                        .filter(l -> l.kind() == side)
                        .toList());
            }

            for (Excursion e : pending) {
                e.extreme = Math.min(e.extreme, low[i]);
            }
            for (KeyLevel level : List.copyOf(active)) {
                if (bars.get(i).tsEvent().isBefore(level.activeFrom())) continue;   // still forming
                if (low[i] < s * level.price()) {
                    active.remove(level);              // liquidity is consumed once traded through
                    pending.add(new Excursion(level, s * level.price(), i, low[i]));
                }
            }
            if (pending.isEmpty()) continue;

            final int bar = i;
            List<Excursion> reclaimed = pending.stream()
                    .filter(e -> close[bar] > e.price)
                    .toList();
            pending.removeAll(reclaimed);
            // not back inside after reclaimBars candles (pierce candle included): breakdown
            pending.removeIf(e -> bar - e.pierceIdx + 1 >= config.reclaimBars());

            List<Excursion> valid = reclaimed.stream()
                    .filter(e -> e.price - e.extreme >= minPenetration)   // a mere poke is no sweep
                    .filter(e -> bar - e.pierceIdx + 1 >= config.minExcursionBars())
                    .sorted(Comparator.comparingDouble(e -> e.price))
                    .toList();
            if (valid.isEmpty() || !config.beforeLastSweep(bars.get(i).tsEvent())) continue;

            out.add(resolve(symbol, bars, direction, s, low, high, close, inSession, i, valid));
        }
        return out;
    }

    /** A key level traded through, tracked until price closes back across it or reclaimBars run out. */
    private static final class Excursion {
        final KeyLevel level;
        final double price;          // level price in "bullish space"
        final int pierceIdx;
        double extreme;              // lowest low since the pierce (bullish space)

        Excursion(KeyLevel level, double price, int pierceIdx, double extreme) {
            this.level = level;
            this.price = price;
            this.pierceIdx = pierceIdx;
            this.extreme = extreme;
        }
    }

    private LiquiditySweep resolve(String symbol, List<TradesBar1m> bars, SweepDirection direction, int s,
                                   double[] low, double[] high, double[] close, boolean[] inSession,
                                   int i, List<Excursion> reclaimed) {
        int n = bars.size();
        Excursion deepest = reclaimed.get(0);
        double extreme = reclaimed.stream().mapToDouble(e -> e.extreme).min().orElseThrow();
        int pierceIdx = reclaimed.stream().mapToInt(e -> e.pierceIdx).min().orElseThrow();
        LocalDate day = config.dayOf(bars.get(i).tsEvent());
        SweepStatus status = SweepStatus.PENDING;
        int confirmIdx = -1;

        if (config.confirmationBars() == 0) {
            status = SweepStatus.CONFIRMED;
            confirmIdx = i;
        } else {
            int k = i + 1;
            for (; k < n && k <= i + config.confirmationBars(); k++) {
                if (!inSession[k] || !config.dayOf(bars.get(k).tsEvent()).equals(day)) {
                    status = SweepStatus.INVALIDATED;   // window closed before confirming
                    break;
                }
                if (low[k] < extreme) {                 // the sweep low itself got taken: failed
                    status = SweepStatus.INVALIDATED;
                    break;
                }
                if (close[k] > high[i]) {
                    status = SweepStatus.CONFIRMED;
                    confirmIdx = k;
                    break;
                }
            }
            if (status == SweepStatus.PENDING && k <= n - 1) {
                status = SweepStatus.INVALIDATED;       // confirmation window fully elapsed
            }
        }

        Double entry = null, stop = null, target = null;
        if (confirmIdx >= 0) {
            double e = close[confirmIdx];
            double risk = e - extreme;
            entry = s * e;
            stop = s * extreme;
            target = s * (e + config.rewardRisk() * risk);
        }

        return new LiquiditySweep(
                symbol,
                direction,
                status,
                deepest.level.price(),
                deepest.level.type(),
                deepest.level.formedAt(),
                reclaimed.stream().map(e -> e.level.type().label).toList(),
                bars.get(pierceIdx).tsEvent(),
                bars.get(i).tsEvent(),
                s * extreme,
                s * close[i],
                confirmIdx >= 0 ? bars.get(confirmIdx).tsEvent() : null,
                entry, stop, target);
    }
}
