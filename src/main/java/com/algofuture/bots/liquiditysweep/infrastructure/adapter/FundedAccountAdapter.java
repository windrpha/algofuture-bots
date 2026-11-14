package com.algofuture.bots.liquiditysweep.infrastructure.adapter;

import com.algofuture.bots.liquiditysweep.application.port.out.AccountsPort;
import com.algofuture.bots.liquiditysweep.domain.model.FundedAccount;
import com.algofuture.shared.infrastructure.persistence.FundedAccountRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** Enabled rows of {@code funded_account}, read fresh on every call so edits in the table apply within a minute. */
@ApplicationScoped
public class FundedAccountAdapter implements AccountsPort {

    private final FundedAccountRepository repository;

    public FundedAccountAdapter(FundedAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<FundedAccount> enabledAccounts() {
        return repository.findEnabled().stream()
                .map(r -> new FundedAccount(r.id(), r.firm(), r.name(), r.maxLoss(), r.maxLossUsed(), r.dailyLoss(),
                        r.riskFraction(), r.maxRiskPerTrade(), r.enabled()))
                .toList();
    }
}
