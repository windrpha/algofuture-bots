package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.ConvictionConfig;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDirection;
import com.algofuture.bots.liquiditysweep.domain.service.SessionSelector.Candidate;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy 4.0: scores every confirmed sweep with the market conditions known at its entry and keeps the
 * first one per day that has enough reasons ({@link ConvictionConfig}). The three price reasons are the ones that
 * held up in both halves of the Jun–Nov 2026 sample; others tried (trend, overnight delta, premium/discount,
 * stop size) flipped sign between halves and were left out. Strategy 4.1 adds the order-flow reason of
 * {@link FootprintReasons}.
 */
public final class ConvictionSelector {

    public enum Reason { KEY_LEVEL, CORRELATED, WITH_GAP, THIN_EXTREME }

    public record Scored(Candidate candidate, List<Reason> reasons) {}

    private ConvictionSelector() {
    }

    /**
     * @param candidates confirmed sweeps of every contract
     * @param gaps       per contract (symbol order), each day's open minus the previous session's close
     * @param footprint  order-flow reason per candidate (4.1); empty for 4.0
     */
    public static List<Scored> onePerDay(List<Candidate> candidates, List<Map<LocalDate, Double>> gaps,
                                         Map<Candidate, List<Reason>> footprint, SweepDetectorConfig config,
                                         ConvictionConfig conviction) {
        List<Scored> qualified = new ArrayList<>();
        Map<Candidate, Scored> byCandidate = new HashMap<>();
        for (Candidate c : candidates) {
            List<Reason> reasons = reasons(c, candidates, gaps.get(c.symbolOrder()), config, conviction);
            reasons.addAll(footprint.getOrDefault(c, List.of()));
            if (reasons.size() < conviction.requiredReasons(c.entryAt().atZone(config.zone()).toLocalTime())) continue;
            Scored s = new Scored(c, reasons);
            qualified.add(s);
            byCandidate.put(c, s);
        }
        return SessionSelector.onePerSession(qualified.stream().map(Scored::candidate).toList(), config.zone())
                .stream().map(byCandidate::get).toList();
    }

    static List<Reason> reasons(Candidate c, List<Candidate> all, Map<LocalDate, Double> gaps,
                                SweepDetectorConfig config, ConvictionConfig conviction) {
        List<Reason> reasons = new ArrayList<>();
        KeyLevel.Type type = c.sweep().levelType();
        if (type != KeyLevel.Type.SWING_HIGH && type != KeyLevel.Type.SWING_LOW) reasons.add(Reason.KEY_LEVEL);
        if (correlated(c, all, conviction.correlationWindow())) reasons.add(Reason.CORRELATED);
        Double gap = gaps.get(config.dayOf(c.entryAt()));
        int sign = c.sweep().direction() == SweepDirection.BULLISH ? 1 : -1;
        if (gap != null && sign * gap > 0) reasons.add(Reason.WITH_GAP);
        return reasons;
    }

    /** Another contract closed a same-direction sweep candle within {@code window} before this entry. */
    private static boolean correlated(Candidate c, List<Candidate> all, Duration window) {
        for (Candidate o : all) {
            if (o.symbolOrder() == c.symbolOrder() || o.sweep().direction() != c.sweep().direction()) continue;
            Instant reclaimClose = o.sweep().sweepAt().plus(barDuration(o));
            Duration lag = Duration.between(reclaimClose, c.entryAt());
            if (!lag.isNegative() && lag.compareTo(window) <= 0) return true;
        }
        return false;
    }

    /** Entry fills at the confirmation candle's close, so entry minus confirmation start is one candle. */
    private static Duration barDuration(Candidate c) {
        return Duration.between(c.sweep().confirmedAt(), c.entryAt());
    }

    /** Per day: open of the first candle of the trading window minus the close of the previous day's last one. */
    public static Map<LocalDate, Double> openingGaps(List<TradesBar1m> bars, SweepDetectorConfig config) {
        Map<LocalDate, Double> open = new LinkedHashMap<>(), close = new LinkedHashMap<>();
        for (TradesBar1m b : bars) {
            if (!config.inSession(b.tsEvent())) continue;
            LocalDate day = config.dayOf(b.tsEvent());
            open.putIfAbsent(day, b.open());
            close.put(day, b.close());
        }
        Map<LocalDate, Double> gaps = new LinkedHashMap<>();
        Double prevClose = null;
        for (Map.Entry<LocalDate, Double> e : open.entrySet()) {
            if (prevClose != null) gaps.put(e.getKey(), e.getValue() - prevClose);
            prevClose = close.get(e.getKey());
        }
        return gaps;
    }
}
