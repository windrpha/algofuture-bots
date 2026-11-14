package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel.Type;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDirection;
import com.algofuture.bots.liquiditysweep.domain.model.SweepStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RangeTargetTest {

    private static final Instant T = Instant.parse("2026-11-11T15:00:00Z");

    /** Entry 100, stop 90 (risk 10), fixed 2R target 120. */
    private static LiquiditySweep sweep(SweepDirection dir) {
        int s = dir == SweepDirection.BULLISH ? 1 : -1;
        return new LiquiditySweep("NQ.FUT", dir, SweepStatus.CONFIRMED, 100 - 5 * s, Type.OPENING_RANGE_LOW, T,
                List.of("ORL"), T, T, 100 - 10 * s, 100, T, 100.0, 100.0 - 10 * s, 100.0 + 20 * s);
    }

    private static List<KeyLevel> range(double high, double low) {
        return List.of(new KeyLevel(Type.OPENING_RANGE_HIGH, high, T, T), new KeyLevel(Type.OPENING_RANGE_LOW, low, T, T));
    }

    @Test
    void long_targetsTheRangeHigh_whenAtLeast1RAway() {
        assertEquals(115.0, RangeTarget.apply(sweep(SweepDirection.BULLISH), range(115, 95)).target());
        assertEquals(135.0, RangeTarget.apply(sweep(SweepDirection.BULLISH), range(135, 95)).target());   // beyond 2R too
    }

    @Test
    void short_targetsTheRangeLow() {
        assertEquals(87.0, RangeTarget.apply(sweep(SweepDirection.BEARISH), range(105, 87)).target());
    }

    @Test
    void rangeCloserThan1R_keepsTheFixedTarget() {
        assertEquals(120.0, RangeTarget.apply(sweep(SweepDirection.BULLISH), range(108, 95)).target());
        assertEquals(120.0, RangeTarget.apply(sweep(SweepDirection.BULLISH), List.of()).target());      // no range levels
    }
}
