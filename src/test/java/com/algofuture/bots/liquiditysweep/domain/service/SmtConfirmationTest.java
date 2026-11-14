package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel.Type;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDirection;
import com.algofuture.bots.liquiditysweep.domain.model.SweepStatus;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmtConfirmationTest {

    private static final Instant PIERCE = Instant.parse("2026-11-11T14:45:00Z");
    private static final Instant RECLAIM = Instant.parse("2026-11-11T14:55:00Z");

    private static LiquiditySweep esSweep(SweepDirection dir, Type type) {
        return new LiquiditySweep("ES.FUT", dir, SweepStatus.CONFIRMED, 7598.25, type, PIERCE, List.of(type.label),
                PIERCE, RECLAIM, 7593.5, 7602, RECLAIM.plusSeconds(300), 7608.25, 7593.5, 7637.75);
    }

    private static TradesBar1m nq(String utc, double high, double low) {
        return new TradesBar1m(Instant.parse(utc), "NQ.FUT", low, high, low, high, 0, 0, 0, 0, 0, 0, 0, Map.of());
    }

    private static KeyLevel level(Type type, double price) {
        return new KeyLevel(type, price, PIERCE.minusSeconds(86400), PIERCE.minusSeconds(3600));
    }

    private static final List<TradesBar1m> NQ_BARS = List.of(
            nq("2026-11-11T14:40:00Z", 28300, 28250),
            nq("2026-11-11T14:50:00Z", 28250, 28199),     // inside the excursion
            nq("2026-11-11T15:05:00Z", 28400, 28050));    // after the reclaim: must not count

    @Test
    void otherIndexSweptItsSameTypeLevel_confirms() {
        assertTrue(SmtConfirmation.confirmed(esSweep(SweepDirection.BULLISH, Type.PREV_DAY_HIGH), NQ_BARS,
                List.of(level(Type.PREV_DAY_HIGH, 28210))));
    }

    @Test
    void otherIndexHeldAbove_isDivergenceNotConfirmation() {
        // 11 Nov 2026: ES swept its PDH from above, NQ held 28199 over its PDH 28105.5
        assertFalse(SmtConfirmation.confirmed(esSweep(SweepDirection.BULLISH, Type.PREV_DAY_HIGH), NQ_BARS,
                List.of(level(Type.PREV_DAY_HIGH, 28105.5))));
    }

    @Test
    void onlyTheSameLevelTypeCounts() {
        assertFalse(SmtConfirmation.confirmed(esSweep(SweepDirection.BULLISH, Type.PREV_DAY_HIGH), NQ_BARS,
                List.of(level(Type.SESSION_LOW, 28210))));
    }

    @Test
    void bearish_needsATradeAboveTheOtherLevel() {
        assertTrue(SmtConfirmation.confirmed(esSweep(SweepDirection.BEARISH, Type.SESSION_HIGH), NQ_BARS,
                List.of(level(Type.SESSION_HIGH, 28240))));
        assertFalse(SmtConfirmation.confirmed(esSweep(SweepDirection.BEARISH, Type.SESSION_HIGH), NQ_BARS,
                List.of(level(Type.SESSION_HIGH, 28350))));
    }
}
