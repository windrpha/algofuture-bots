package com.algofuture.bots.liquiditysweep.infrastructure.health;

import com.algofuture.bots.liquiditysweep.application.service.LiveAlertService;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;

import java.time.Duration;
import java.time.Instant;

/**
 * Liveness: in session the alert loop must finish a run every minute; several minutes without one means it is stuck
 * and the container should be replaced. Outside the session (or with alerts off) there is nothing to run, so it is UP.
 * Only failures of the loop itself count here: a database or feed outage is a readiness matter, since restarting the
 * container would not fix it.
 */
@Liveness
@ApplicationScoped
public class AlertLoopLivenessCheck implements HealthCheck {

    static final Duration MAX_SILENCE = Duration.ofMinutes(5);

    private final LiveAlertService service;

    public AlertLoopLivenessCheck(LiveAlertService service) {
        this.service = service;
    }

    @Override
    public HealthCheckResponse call() {
        Instant now = Instant.now();
        HealthCheckResponseBuilder r = HealthCheckResponse.named("alert-loop")
                .withData("enabled", service.enabled())
                .withData("inSession", LiveAlertService.inSession(now))
                .withData("lastRun", String.valueOf(service.lastRunOrStart()));
        if (service.lastError() != null) {
            r.withData("lastFailure", String.valueOf(service.lastFailure())).withData("lastError", service.lastError());
        }
        boolean stuck = service.enabled() && LiveAlertService.inSession(now)
                && Duration.between(service.lastRunOrStart(), now).compareTo(MAX_SILENCE) > 0;
        return r.status(!stuck).build();
    }
}
