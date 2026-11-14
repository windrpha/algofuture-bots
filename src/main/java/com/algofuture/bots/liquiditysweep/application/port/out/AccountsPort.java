package com.algofuture.bots.liquiditysweep.application.port.out;

import com.algofuture.bots.liquiditysweep.domain.model.FundedAccount;

import java.util.List;

/** The funded accounts to size live alerts for (the {@code funded_account} table), read fresh on every call. */
public interface AccountsPort {

    List<FundedAccount> enabledAccounts();
}
