package com.algofuture.bots.liquiditysweep.infrastructure.adapter;

import com.algofuture.bots.liquiditysweep.application.port.out.LiveTradePort;
import com.algofuture.shared.infrastructure.persistence.LiveTradeRepository;
import com.algofuture.shared.journal.domain.model.LiveTradeRecord;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Thin adapter: delegates to the shared {@code live_trade} repository. */
@ApplicationScoped
public class LiveTradeAdapter implements LiveTradePort {

    private final LiveTradeRepository repository;

    public LiveTradeAdapter(LiveTradeRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<LiveTradeRecord> findDay(LocalDate day) {
        return repository.findDay(day);
    }

    @Override
    public boolean insert(LiveTradeRecord trade) {
        return repository.insert(trade);
    }

    @Override
    public boolean close(long id, String exitType, Instant exitTime, double exitPrice, double pnlUsd) {
        return repository.close(id, exitType, exitTime, exitPrice, pnlUsd);
    }

    @Override
    public boolean recordSkippedOutcome(long id, String exitType, Instant exitTime, double exitPrice) {
        return repository.recordSkippedOutcome(id, exitType, exitTime, exitPrice);
    }

    @Override
    public int markReminderSent(List<Long> ids) {
        return repository.markReminderSent(ids);
    }
}
