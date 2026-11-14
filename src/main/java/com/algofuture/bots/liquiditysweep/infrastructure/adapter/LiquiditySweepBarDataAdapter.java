package com.algofuture.bots.liquiditysweep.infrastructure.adapter;

import com.algofuture.bots.liquiditysweep.application.port.out.LiquiditySweepBarDataPort;
import com.algofuture.shared.infrastructure.persistence.MarketDataRepository;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Thin adapter: delegates to the shared repository, loading the (heavy) footprint only when asked for. */
@ApplicationScoped
public class LiquiditySweepBarDataAdapter implements LiquiditySweepBarDataPort {

    private final MarketDataRepository repository;

    public LiquiditySweepBarDataAdapter(MarketDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<TradesBar1m> findBars(String symbol, OffsetDateTime from, OffsetDateTime to, String interval) {
        return repository.findTradesBar1mBySymbolAndRange(symbol, from, to, interval, false);
    }

    @Override
    public List<TradesBar1m> findMinutesWithFootprint(String symbol, OffsetDateTime from, OffsetDateTime to) {
        return repository.findTradesBar1mBySymbolAndRange(symbol, from, to, "1m", true);
    }

    @Override
    public Optional<Double> findTickSize(String symbol) {
        return repository.findTickSize(symbol);
    }
}
