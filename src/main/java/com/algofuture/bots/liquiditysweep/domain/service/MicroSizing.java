package com.algofuture.bots.liquiditysweep.domain.service;

import java.util.Map;
import java.util.Optional;

/**
 * Sizes a signal in micro contracts for a fixed dollar risk: a $25k funded account cannot size finer than one micro,
 * and one NQ mini on a typical 80 point stop risks $1,600.
 */
public final class MicroSizing {

    /** Micro contract of a mini root, its dollars per point and how a trader calls it. */
    private record Micro(String symbol, double pointValue, String description) {}

    private static final Map<String, Micro> MICROS = Map.of(
            "NQ", new Micro("MNQ", 2.0, "Micro Nasdaq"), "ES", new Micro("MES", 5.0, "Micro S&P 500"),
            "YM", new Micro("MYM", 0.5, "Micro Dow"), "RTY", new Micro("M2K", 5.0, "Micro Russell"));

    /**
     * @param riskUsd     dollars lost if the stop is hit with {@code contracts} micros
     * @param usdPerPoint dollars per index point for the whole position ({@code contracts} × the micro's point value)
     */
    public record Size(String micro, String description, int contracts, double riskUsd, double usdPerPoint) {

        /** Dollars made or lost moving {@code points} in the trade's favor (negative = against). */
        public double usd(double points) {
            return points * usdPerPoint;
        }
    }

    private MicroSizing() {
    }

    /**
     * As many micros as fit in {@code budget}, at least one while a single micro risks no more than {@code maxRisk};
     * empty when even one micro risks more (the signal is skipped).
     */
    public static Optional<Size> of(String root, double riskPoints, double budget, double maxRisk) {
        Micro micro = MICROS.get(root);
        if (micro == null) throw new IllegalArgumentException("No micro contract known for " + root);
        double perContract = riskPoints * micro.pointValue();
        if (perContract > maxRisk) return Optional.empty();
        int contracts = Math.max(1, (int) Math.floor(budget / perContract));
        return Optional.of(new Size(micro.symbol(), micro.description(), contracts, contracts * perContract,
                contracts * micro.pointValue()));
    }

    /** Dollars per index point of {@code contracts} micros of a mini root. */
    public static double usdPerPoint(String root, int contracts) {
        Micro micro = MICROS.get(root);
        if (micro == null) throw new IllegalArgumentException("No micro contract known for " + root);
        return contracts * micro.pointValue();
    }

    /** The micro's symbol ("MNQ" for "NQ"). */
    public static String microOf(String root) {
        Micro micro = MICROS.get(root);
        if (micro == null) throw new IllegalArgumentException("No micro contract known for " + root);
        return micro.symbol();
    }

    /** One micro's risk, for the message that explains a skipped signal. */
    public static Size single(String root, double riskPoints) {
        Micro micro = MICROS.get(root);
        return new Size(micro.symbol(), micro.description(), 1, riskPoints * micro.pointValue(), micro.pointValue());
    }
}
