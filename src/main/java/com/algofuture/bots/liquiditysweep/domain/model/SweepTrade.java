package com.algofuture.bots.liquiditysweep.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * A confirmed sweep traded to its exit. One row of {@code strategy_trade}.
 *
 * {@code entryAt} is the CLOSE time of the confirmation candle (the entry fills at its close).
 * For stop/target exits {@code exitAt} is the START of the candle that touched the level (the
 * intrabar minute is unknown at this timeframe); for time exits it is that candle's close.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SweepTrade(
        LiquiditySweep sweep,
        Instant entryAt,
        double entryPrice,
        double stopLoss,
        double takeProfit,
        Instant exitAt,
        Double exitPrice,
        ExitType exitType,
        TradeResult result,
        double rrPlanned,
        Double rrRealized,
        Integer pnlTicks,
        Double pnl
) {
    @JsonIgnore
    public boolean isLong() {
        return sweep.direction() == SweepDirection.BULLISH;
    }
}
