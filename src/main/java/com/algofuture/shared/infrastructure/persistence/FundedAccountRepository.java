package com.algofuture.shared.infrastructure.persistence;

import com.algofuture.shared.journal.domain.model.FundedAccountRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;

/** The {@code funded_account} catalog, SHARED by every bot that alerts per prop-firm account. */
@ApplicationScoped
public class FundedAccountRepository {

    @Inject
    EntityManager entityManager;

    @SuppressWarnings("unchecked")
    public List<FundedAccountRecord> findEnabled() {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT id, firm, name, account_number, max_loss, max_loss_used, daily_loss, risk_fraction,
                               max_risk_per_trade, enabled
                        FROM funded_account
                        WHERE enabled
                        ORDER BY created_at, id
                        """)
                .getResultList();
        return rows.stream().map(r -> new FundedAccountRecord(
                (String) r[0], (String) r[1], (String) r[2], (String) r[3],
                num(r[4]), num(r[5]), num(r[6]), num(r[7]),
                r[8] == null ? null : num(r[8]), (Boolean) r[9])).toList();
    }

    private static double num(Object v) {
        return ((Number) v).doubleValue();
    }
}
