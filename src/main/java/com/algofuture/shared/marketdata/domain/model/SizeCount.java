package com.algofuture.shared.marketdata.domain.model;

public record SizeCount(
        long contractSize,
        int  buyCount,
        int  sellCount
) {
    public int totalCount() { return buyCount + sellCount; }
}
