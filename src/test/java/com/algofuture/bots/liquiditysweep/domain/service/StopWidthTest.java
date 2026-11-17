package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.ContractSpec;
import com.algofuture.bots.liquiditysweep.domain.model.ConvictionConfig;
import com.algofuture.bots.liquiditysweep.domain.model.FootprintRules;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel.Type;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDirection;
import com.algofuture.bots.liquiditysweep.domain.model.SweepStatus;
import com.algofuture.bots.liquiditysweep.domain.service.ConvictionSelector.Scored;
import com.algofuture.bots.liquiditysweep.domain.service.SessionSelector.Candidate;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StopWidthTest {

    private static final Instant T0 = Instant.parse("2026-11-16T12:00:00Z");
    private static final Duration BAR = Duration.ofMinutes(5);

    /** {@code n} candles of range {@code range} from T0, then one confirmation candle. */
    private static List<TradesBar1m> bars(int n, double range) {
        List<TradesBar1m> out = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            out.add(new TradesBar1m(T0.plus(BAR.multipliedBy(i)), "NQ.FUT", 100, 100 + range, 100, 100,
                    0, 0, 0, 0, 0, 0, 0, Map.of()));
        }
        return out;
    }

    private static LiquiditySweep sweep(Instant confirmedAt, double entry, double stop) {
        return new LiquiditySweep("NQ.FUT", SweepDirection.BULLISH, SweepStatus.CONFIRMED, 100, Type.SESSION_LOW,
                T0, List.of("ONL"), T0, T0, stop, 101, confirmedAt, entry, stop, entry + 3 * (entry - stop));
    }

    @Test
    void riskOverTheMeanRangeOfTheCandlesBeforeTheConfirmation() {
        List<TradesBar1m> bars = bars(30, 10);                       // 10 points per candle
        Instant confirm = bars.get(30).tsEvent();
        assertEquals(4.0, StopWidth.riskOverAtr(sweep(confirm, 140, 100), bars), 1e-9);
    }

    @Test
    void onlyTheLast24CandlesCount_andTheConfirmationCandleItselfDoesNot() {
        List<TradesBar1m> bars = bars(30, 10);
        // a huge old candle (outside the 24) and a huge confirmation candle: neither moves the ATR
        bars.set(0, new TradesBar1m(T0, "NQ.FUT", 100, 1000, 100, 100, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        TradesBar1m c = bars.get(30);
        bars.set(30, new TradesBar1m(c.tsEvent(), "NQ.FUT", 100, 1000, 100, 100, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        assertEquals(2.0, StopWidth.riskOverAtr(sweep(c.tsEvent(), 120, 100), bars), 1e-9);
    }

    @Test
    void unknownWhenTheConfirmationHasNoHistory() {
        List<TradesBar1m> bars = bars(5, 10);
        assertTrue(Double.isNaN(StopWidth.riskOverAtr(sweep(bars.get(0).tsEvent(), 120, 100), bars)));
        assertTrue(Double.isNaN(StopWidth.riskOverAtr(sweep(T0.minus(BAR), 120, 100), bars)));
    }

    @Test
    void tooWideStop_doesNotQualify_andTheDayTakesTheNextSweep() {
        SweepDetectorConfig config = SweepDetectorConfig.DAILY;
        ConvictionConfig v43 = ConvictionConfig.WITH_STOP_WIDTH;
        ContractSpec nq = ContractSpec.of("NQ.FUT", 0.25);
        Instant wideAt = Instant.parse("2026-11-16T15:45:00Z"), laterAt = Instant.parse("2026-11-16T16:30:00Z"); // after 10:00 CT
        Candidate wide = new Candidate(nq, 0, sweep(wideAt.minus(BAR), 140, 100), wideAt);
        Candidate later = new Candidate(nq, 0, sweep(laterAt.minus(BAR), 120, 100), laterAt);
        List<Map<LocalDate, Double>> gaps = List.of(Map.of(LocalDate.of(2026, 11, 16), 5.0), Map.of());

        List<Scored> picks = ConvictionSelector.onePerDay(List.of(wide, later), gaps, Map.of(),
                Map.of(wide, 4.1, later, 2.0), config, v43);
        assertEquals(List.of(later), picks.stream().map(Scored::candidate).toList());

        // 4.2 has no limit: the first one is taken
        picks = ConvictionSelector.onePerDay(List.of(wide, later), gaps, Map.of(),
                Map.of(wide, 4.1, later, 2.0), config, ConvictionConfig.WITH_STOP_BUFFER);
        assertEquals(List.of(wide), picks.stream().map(Scored::candidate).toList());
    }

    @Test
    void config_namesTheStrategy_andTheLimitNeedsAStopBuffer() {
        assertEquals("liquidity sweep 4.3", ConvictionConfig.WITH_STOP_WIDTH.strategyName());
        assertEquals(3.0, ConvictionConfig.WITH_STOP_WIDTH.maxRiskAtr());
        assertTrue(ConvictionConfig.WITH_STOP_WIDTH.stopNarrowEnough(Double.NaN));
        assertTrue(ConvictionConfig.WITH_STOP_BUFFER.stopNarrowEnough(10));
        assertThrows(IllegalArgumentException.class, () -> new ConvictionConfig(LocalTime.of(10, 0), 2, 1,
                Duration.ofMinutes(45), 3, FootprintRules.DEFAULT, Map.of(), 3.0));
    }
}
