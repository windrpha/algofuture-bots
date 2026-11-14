package com.algofuture.bots.liquiditysweep.application.port.out;

import com.algofuture.shared.marketdata.domain.model.TradesBar1m;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** The market data this bot needs: OHLC candles, the footprint of a few minutes, and the contract tick size. */
public interface LiquiditySweepBarDataPort {

    List<TradesBar1m> findBars(String symbol, OffsetDateTime from, OffsetDateTime to, String interval);

    /** 1m candles WITH their footprint: heavy, so callers ask only for the few minutes they need. */
    List<TradesBar1m> findMinutesWithFootprint(String symbol, OffsetDateTime from, OffsetDateTime to);

    /** {@code instrument.tick_size}, empty when the contract is not registered. */
    Optional<Double> findTickSize(String symbol);
}
