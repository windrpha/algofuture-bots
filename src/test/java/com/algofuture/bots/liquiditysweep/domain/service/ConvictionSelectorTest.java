package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.ContractSpec;
import com.algofuture.bots.liquiditysweep.domain.model.ConvictionConfig;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel.Type;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDirection;
import com.algofuture.bots.liquiditysweep.domain.model.SweepStatus;
import com.algofuture.bots.liquiditysweep.domain.service.ConvictionSelector.Reason;
import com.algofuture.bots.liquiditysweep.domain.service.ConvictionSelector.Scored;
import com.algofuture.bots.liquiditysweep.domain.service.SessionSelector.Candidate;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConvictionSelectorTest {

    private static final SweepDetectorConfig CONFIG = SweepDetectorConfig.DEFAULT;
    private static final ConvictionConfig CONVICTION = ConvictionConfig.DEFAULT;
    private static final ContractSpec NQ = ContractSpec.of("NQ.FUT", 0.25);
    private static final ContractSpec ES = ContractSpec.of("ES.FUT", 0.25);
    private static final LocalDate DAY = LocalDate.of(2026, 11, 11);
    private static final Duration BAR = Duration.ofMinutes(5);

    /** Sweep reclaimed 10 minutes and confirmed 5 minutes before {@code entryUtc}. */
    private static Candidate candidate(ContractSpec c, int order, Type type, SweepDirection dir, String entryUtc) {
        Instant entry = Instant.parse(entryUtc);
        LiquiditySweep sweep = new LiquiditySweep(c.sourceSymbol(), dir, SweepStatus.CONFIRMED, 100, type, entry,
                List.of(type.label), entry.minus(BAR.multipliedBy(3)), entry.minus(BAR.multipliedBy(2)), 99, 101,
                entry.minus(BAR), 102.0, 99.0, 111.0);
        return new Candidate(c, order, sweep, entry);
    }

    private static List<Map<LocalDate, Double>> gaps(double nq, double es) {
        return List.of(Map.of(DAY, nq), Map.of(DAY, es));
    }

    @Test
    void early_oneReasonIsNotEnough_twoAre() {
        Candidate keyAgainstGap = candidate(NQ, 0, Type.HOUR_LOW, SweepDirection.BULLISH, "2026-11-11T14:55:00Z");
        assertEquals(0, ConvictionSelector.onePerDay(List.of(keyAgainstGap), gaps(-5, 0), Map.of(), CONFIG, CONVICTION).size());

        List<Scored> picks = ConvictionSelector.onePerDay(List.of(keyAgainstGap), gaps(5, 0), Map.of(), CONFIG, CONVICTION);
        assertEquals(List.of(Reason.KEY_LEVEL, Reason.WITH_GAP), picks.get(0).reasons());
    }

    @Test
    void late_oneReasonIsEnough() {
        Candidate swingWithGap = candidate(ES, 1, Type.SWING_HIGH, SweepDirection.BEARISH, "2026-11-11T17:00:00Z"); // 11:00 CT
        List<Scored> picks = ConvictionSelector.onePerDay(List.of(swingWithGap), gaps(0, -3), Map.of(), CONFIG, CONVICTION);
        assertEquals(List.of(Reason.WITH_GAP), picks.get(0).reasons());
    }

    @Test
    void correlated_onlyWhenTheOtherIndexSweptBeforeTheEntry() {
        Candidate nq = candidate(NQ, 0, Type.SWING_LOW, SweepDirection.BULLISH, "2026-11-11T15:00:00Z");
        Candidate esBefore = candidate(ES, 1, Type.SWING_LOW, SweepDirection.BULLISH, "2026-11-11T14:50:00Z");
        Candidate esAfter = candidate(ES, 1, Type.SWING_LOW, SweepDirection.BULLISH, "2026-11-11T15:30:00Z");

        assertEquals(List.of(Reason.CORRELATED), ConvictionSelector.reasons(nq, List.of(nq, esBefore), Map.of(), CONFIG, CONVICTION));
        assertEquals(List.of(), ConvictionSelector.reasons(nq, List.of(nq, esAfter), Map.of(), CONFIG, CONVICTION));
    }

    @Test
    void firstQualifyingEntryOfTheDayWins() {
        Candidate early = candidate(NQ, 0, Type.SWING_LOW, SweepDirection.BULLISH, "2026-11-11T14:55:00Z");   // 0 reasons
        Candidate later = candidate(ES, 1, Type.PREV_DAY_LOW, SweepDirection.BULLISH, "2026-11-11T15:10:00Z");
        Candidate last = candidate(NQ, 0, Type.SESSION_LOW, SweepDirection.BULLISH, "2026-11-11T16:30:00Z");
        List<Scored> picks = ConvictionSelector.onePerDay(List.of(early, later, last), gaps(-1, 2), Map.of(), CONFIG, CONVICTION);
        assertEquals(1, picks.size());
        assertEquals("ES", picks.get(0).candidate().contract().root());
    }

    @Test
    void footprintReason_countsTowardsTheRequirement() {
        Candidate swing = candidate(NQ, 0, Type.SWING_LOW, SweepDirection.BULLISH, "2026-11-11T14:55:00Z");   // 08:55 CT
        ConvictionConfig v41 = ConvictionConfig.WITH_FOOTPRINT;                                                   // 2 early
        assertEquals(0, ConvictionSelector.onePerDay(List.of(swing), gaps(5, 0), Map.of(), CONFIG, v41).size());
        List<Scored> picks = ConvictionSelector.onePerDay(List.of(swing), gaps(5, 0),
                Map.of(swing, List.of(Reason.THIN_EXTREME)), CONFIG, v41);
        assertEquals(List.of(Reason.WITH_GAP, Reason.THIN_EXTREME), picks.get(0).reasons());
    }

    @Test
    void openingGap_isOpenMinusPreviousSessionClose() {
        List<TradesBar1m> bars = List.of(
                bar("2026-11-10T20:55:00Z", 100, 105),   // 14:55 CT, last candle of the 10th
                bar("2026-11-10T21:00:00Z", 105, 200),   // 15:00 CT, outside the window
                bar("2026-11-11T14:30:00Z", 108, 109));  // 08:30 CT open of the 11th
        assertEquals(Map.of(DAY, 3.0), ConvictionSelector.openingGaps(bars, CONFIG));
    }

    private static TradesBar1m bar(String t, double open, double close) {
        return new TradesBar1m(Instant.parse(t), "NQ.FUT", open, Math.max(open, close), Math.min(open, close), close,
                0, 0, 0, 0, 0, 0, 0, Map.of());
    }
}
