package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.ConvictionConfig;
import com.algofuture.bots.liquiditysweep.domain.model.FootprintRules;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel.Type;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDirection;
import com.algofuture.bots.liquiditysweep.domain.model.SweepStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StopBufferTest {

    private static final Instant T = Instant.parse("2026-11-11T15:00:00Z");

    private static LiquiditySweep sweep(SweepDirection dir, double entry, double stop, double target) {
        return new LiquiditySweep("ES.FUT", dir, SweepStatus.CONFIRMED, 100, Type.SESSION_LOW, T, List.of("ONL"),
                T, T, stop, 101, T, entry, stop, target);
    }

    @Test
    void long_stopMovesDown_targetFollowsAtTheSameR() {
        // entry 110, wick 100 (risk 10, target 140) + 2 points → stop 98, risk 12, target 146
        LiquiditySweep s = StopBuffer.apply(sweep(SweepDirection.BULLISH, 110, 100, 140), 2.0, 3.0);
        assertEquals(98, s.stop());
        assertEquals(146, s.target());
        assertEquals(100, s.sweepExtreme());   // the sweep itself is unchanged
    }

    @Test
    void short_mirrors() {
        LiquiditySweep s = StopBuffer.apply(sweep(SweepDirection.BEARISH, 100, 110, 70), 2.0, 3.0);
        assertEquals(112, s.stop());
        assertEquals(64, s.target());
    }

    @Test
    void zeroBuffer_returnsTheSameSweep() {
        LiquiditySweep s = sweep(SweepDirection.BULLISH, 110, 100, 140);
        assertSame(s, StopBuffer.apply(s, 0, 3.0));
    }

    @Test
    void config_namesTheStrategy_andBufferNeedsFootprint() {
        assertEquals("liquidity sweep 4.0", ConvictionConfig.DEFAULT.strategyName());
        assertEquals("liquidity sweep 4.1", ConvictionConfig.WITH_FOOTPRINT.strategyName());
        assertEquals("liquidity sweep 4.2", ConvictionConfig.WITH_STOP_BUFFER.strategyName());
        assertEquals(8, ConvictionConfig.WITH_STOP_BUFFER.stopBufferTicks("ES"));
        assertEquals(0, ConvictionConfig.WITH_STOP_BUFFER.stopBufferTicks("NQ"));
        assertThrows(IllegalArgumentException.class, () -> new ConvictionConfig(LocalTime.of(10, 0), 2, 1,
                Duration.ofMinutes(45), 3, null, Map.of("ES", 8)));
        assertEquals("liquidity sweep 4.1", new ConvictionConfig(LocalTime.of(10, 0), 2, 1, Duration.ofMinutes(45), 3,
                FootprintRules.DEFAULT, Map.of("ES", 0)).strategyName());
    }
}
