package com.algofuture.shared.marketdata.domain.model;

import java.util.NavigableMap;

public record FootprintLevel(
        double                        price,
        long                          buyVolume,
        long                          sellVolume,
        int                           buyTrades,
        int                           sellTrades,
        NavigableMap<Long, SizeCount> sizeDistribution   // contractSize → counts
) {
    public long   totalVolume()  { return buyVolume + sellVolume; }
    public int    totalTrades()  { return buyTrades + sellTrades; }
    public long   delta()        { return buyVolume - sellVolume; }
    public double deltaPercent() {
        return totalVolume() == 0 ? 0.0
                : (double) delta() / totalVolume() * 100.0;
    }

    public long dominantBuySize() {
        return sizeDistribution.values().stream()
                .max(java.util.Comparator.comparingInt(SizeCount::buyCount))
                .map(SizeCount::contractSize)
                .orElse(0L);
    }

    public long dominantSellSize() {
        return sizeDistribution.values().stream()
                .max(java.util.Comparator.comparingInt(SizeCount::sellCount))
                .map(SizeCount::contractSize)
                .orElse(0L);
    }
}