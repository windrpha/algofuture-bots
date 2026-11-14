package com.algofuture.shared.journal.domain.model;

/** One {@code funded_account} row: a prop-firm account live alerts are sized for. */
public record FundedAccountRecord(String id, String firm, String name, String accountNumber, double maxLoss,
                                  double maxLossUsed, double dailyLoss, double riskFraction, Double maxRiskPerTrade,
                                  boolean enabled) {
}
