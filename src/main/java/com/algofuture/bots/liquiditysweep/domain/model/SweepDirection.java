package com.algofuture.bots.liquiditysweep.domain.model;

/**
 * BULLISH: price ran the stops resting BELOW a prior low and closed back above it (long bias).
 * BEARISH: mirror case, stops above a prior high were taken and price closed back below it.
 */
public enum SweepDirection { BULLISH, BEARISH }
