package com.algofuture.bots.liquiditysweep.infrastructure.health;

import com.algofuture.bots.liquiditysweep.application.port.out.AlertPort;
import com.algofuture.bots.liquiditysweep.application.port.out.LiquiditySweepBarDataPort;
import com.algofuture.bots.liquiditysweep.application.service.LiveAlertService;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Readiness of the live alerts (the database connection itself has its own automatic check): with alerts on,
 * Telegram must be configured and, in session, every symbol's latest 1m candle must be recent, i.e. the feed that
 * fills trade_1m_live is running. Outside the session stale data is expected and not a problem.
 */
@Readiness
@ApplicationScoped
public class AlertReadinessCheck implements HealthCheck {

    private static final Duration LOOKBACK = Duration.ofMinutes(30);
    private static final Duration MAX_DATA_AGE = Duration.ofMinutes(5);

    private final LiveAlertService service;
    private final AlertPort alerts;
    private final LiquiditySweepBarDataPort barData;
    private final List<String> symbols;

    public AlertReadinessCheck(LiveAlertService service, AlertPort alerts, LiquiditySweepBarDataPort barData,
                               @ConfigProperty(name = "alerts.symbols", defaultValue = "NQ.FUT,ES.FUT") List<String> symbols) {
        this.service = service;
        this.alerts = alerts;
        this.barData = barData;
        this.symbols = symbols;
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder r = HealthCheckResponse.named("live-alerts").withData("enabled", service.enabled());
        if (!service.enabled()) return r.up().build();
        boolean up = alerts.configured();
        r.withData("telegram", alerts.configured());

        Instant now = Instant.now();
        boolean inSession = LiveAlertService.inSession(now);
        r.withData("inSession", inSession);
        try {
            for (String symbol : symbols) {
                List<TradesBar1m> recent = barData.findBars(symbol, now.minus(LOOKBACK).atOffset(ZoneOffset.UTC),
                        now.atOffset(ZoneOffset.UTC), "1m");
                Instant last = recent.isEmpty() ? null : recent.get(recent.size() - 1).tsEvent();
                r.withData(symbol + ".lastCandle", String.valueOf(last));
                if (inSession && (last == null || Duration.between(last, now).compareTo(MAX_DATA_AGE) > 0)) up = false;
            }
        } catch (RuntimeException e) {
            r.withData("marketDataError", String.valueOf(e.getMessage()));
            up = false;
        }
        return r.status(up).build();
    }
}
