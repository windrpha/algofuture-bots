package com.algofuture.bots.liquiditysweep.domain.model;

/**
 * A prop-firm account the live alerts size for. Every account takes the same signal, each with its own risk.
 *
 * @param id            short unique id ("nys-25k-1")
 * @param firm          prop firm shown in the alert ("NYS")
 * @param name          account name shown in the alert ("The Thunder 25k")
 * @param maxLoss       the firm's max loss (trailing drawdown) for the account, in dollars
 * @param maxLossUsed   how much of it is used right now (the dashboard's "Max Loss" used part), updated by hand
 * @param dailyLoss     the firm's daily loss / pause limit; a trade never risks more than this
 * @param riskFraction  share of the remaining room one trade may risk (0.20 ≈ five straight losses before the limit)
 * @param maxRiskPerTrade optional hard cap in dollars that overrides the fraction rule ({@code null} = use the rule)
 * @param enabled       false keeps the account on file without alerting for it
 */
public record FundedAccount(String id, String firm, String name, double maxLoss, double maxLossUsed, double dailyLoss,
                            double riskFraction, Double maxRiskPerTrade, boolean enabled) {

    /**
     * On the NYS 25k account ($1,250 trailing max loss, $637 left) the 4.2 trades simulated best when one micro could
     * risk ~$125, i.e. 20% of the room; with the full $1,250 room the same share is $250.
     */
    public static final double DEFAULT_RISK_FRACTION = 0.20;

    public FundedAccount {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("account id is required");
        if (riskFraction <= 0 || riskFraction > 1) throw new IllegalArgumentException("riskFraction must be in (0, 1]");
    }

    /** Dollars left before the max loss is hit. */
    public double room() {
        return Math.max(0, maxLoss - maxLossUsed);
    }

    /** Most a single trade may risk on this account. */
    public double maxRisk() {
        if (maxRiskPerTrade != null) return maxRiskPerTrade;
        double byRoom = room() * riskFraction;
        return dailyLoss > 0 ? Math.min(byRoom, dailyLoss) : byRoom;
    }

    /** "NYS · The Thunder 25k" */
    public String label() {
        return firm == null || firm.isBlank() ? name : firm + " · " + name;
    }
}
