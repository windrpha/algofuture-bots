package com.algofuture.bots.liquiditysweep.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * One liquidity sweep: inside the trading window a candle trades beyond a session / day / week
 * extreme (taking the stops parked there) and closes back on the other side of it.
 *
 * <pre>
 *   key level (PDL…)   sweep candle     confirmation candle
 *                           │                ┌─┐  ← closes above the sweep candle's high
 *                         ┌─┴─┐              │ │
 *                         └─┬─┘              └─┘
 *  ──────── sweptLevel ─────│──────────────────────
 *                           └── sweepExtreme (stop)
 * </pre>
 *
 * {@code sweptLevel}/{@code levelType} is the deepest level the candle closed back across;
 * {@code levelsSwept} lists every key level it reclaimed (e.g. [PDL, ONL]).
 * {@code piercedAt} is the candle that first traded through the level and {@code sweepAt} the one
 * that closed back across it (the same candle in a one-candle sweep); {@code sweepExtreme} is the
 * excursion's extreme between them.
 * Trade fields ({@code entry}, {@code stop}, {@code target}) are set only when CONFIRMED.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LiquiditySweep(
        String symbol,
        SweepDirection direction,
        SweepStatus status,
        double sweptLevel,
        KeyLevel.Type levelType,
        Instant levelAt,
        List<String> levelsSwept,
        Instant piercedAt,
        Instant sweepAt,
        double sweepExtreme,
        double sweepClose,
        Instant confirmedAt,
        Double entry,
        Double stop,
        Double target
) {
    /** How far beyond the level the wick traded. */
    public double penetration() {
        return Math.abs(sweepExtreme - sweptLevel);
    }
}
