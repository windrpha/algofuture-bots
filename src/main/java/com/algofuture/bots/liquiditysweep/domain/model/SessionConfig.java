package com.algofuture.bots.liquiditysweep.domain.model;

import java.time.LocalTime;

/**
 * One trade per session across several correlated contracts (strategy 3.0).
 *
 * @param lastEntry        latest entry time (zone of the sweep config): the opening window is
 *                         session start → {@code lastEntry}
 * @param smtConfirmation  only sweeps where another of the contracts also traded through its own
 *                         level of the same type during the excursion (both indices raided liquidity)
 * @param rangeTarget      take profit at the opposite side of the opening range when it is at least 1R away,
 *                         otherwise at the fixed rewardRisk target (strategy 3.2; needs openingRangeMinutes)
 */
public record SessionConfig(LocalTime lastEntry, boolean smtConfirmation, boolean rangeTarget) {

    public static final SessionConfig DEFAULT = new SessionConfig(LocalTime.of(10, 0), true, false);

    /** 3.1 adds the opening range to the levels of 3.0; 3.2 also targets its opposite side. */
    public String strategyName(SweepDetectorConfig config) {
        if (rangeTarget) return "liquidity sweep 3.2";
        return config.openingRangeMinutes() > 0 ? "liquidity sweep 3.1" : "liquidity sweep 3.0";
    }
}
