package com.algofuture.bots.liquiditysweep.domain.model;

/**
 * Order-flow reason of strategy 4.1, read from the footprint of the sweep's excursion (pierce candle → reclaim
 * candle). Of ten footprint measures tried on NQ/ES Jun–Nov 2026, this is the only one that held in both halves of
 * the sample and was not present on nearly every sweep (a "trapped breakout traders" diagonal imbalance also held,
 * but fired on 98% of sweeps, so it carried no information).
 *
 * @param thinExtremeRatio THIN_EXTREME when the volume of the two most extreme prices of the excursion is at most
 *                         this fraction of an average price's volume: nobody kept trading out there, the auction
 *                         finished (0.19 = the lowest third of Jun–Aug sweeps)
 */
public record FootprintRules(double thinExtremeRatio) {

    public static final FootprintRules DEFAULT = new FootprintRules(0.19);

    public FootprintRules {
        if (thinExtremeRatio < 0) throw new IllegalArgumentException("thinExtremeRatio must be >= 0");
    }
}
