package com.algofuture.shared.marketdata.domain.model;

import java.time.Instant;
import java.util.Map;

public record TradesBar1m(
        Instant tsEvent,
        String  symbol,
        double  open,
        double  high,
        double  low,
        double  close,
        long    volume,
        long    buyVolume,
        long    sellVolume,
        long    delta,
        long    deltaAcc,
        int     tradeCount,
        double  avgTradeSize,
        Map<Double, FootprintLevel> footprint
) {}
