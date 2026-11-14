package com.algofuture.shared.marketdata.domain.model;

import java.time.OffsetDateTime;

public record SessionHighLow(
        String symbol,
        double sessionHigh,
        double sessionLow,
        OffsetDateTime highAt,
        OffsetDateTime lowAt,
        OffsetDateTime sessionFrom,
        OffsetDateTime sessionTo
) {}