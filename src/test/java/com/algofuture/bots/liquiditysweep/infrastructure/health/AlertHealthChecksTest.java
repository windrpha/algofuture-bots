package com.algofuture.bots.liquiditysweep.infrastructure.health;

import com.algofuture.bots.liquiditysweep.application.port.out.AlertPort;
import com.algofuture.bots.liquiditysweep.application.port.out.LiquiditySweepBarDataPort;
import com.algofuture.bots.liquiditysweep.application.service.LiveAlertService;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The checks against a real service instance; the session-dependent branches are covered via LiveAlertService. */
class AlertHealthChecksTest {

    private static LiveAlertService service(boolean enabled) {
        return new LiveAlertService(null, null, null, null, null, null, enabled, List.of("NQ.FUT"));
    }

    private static AlertPort telegram(boolean configured) {
        return new AlertPort() {
            public boolean configured() { return configured; }
            public void send(String text) { }
        };
    }

    private static final LiquiditySweepBarDataPort NO_DATA = new LiquiditySweepBarDataPort() {
        public List<TradesBar1m> findBars(String s, OffsetDateTime f, OffsetDateTime t, String i) { return List.of(); }
        public List<TradesBar1m> findMinutesWithFootprint(String s, OffsetDateTime f, OffsetDateTime t) { return List.of(); }
        public Optional<Double> findTickSize(String s) { return Optional.empty(); }
    };

    @Test
    void alertsOff_bothUp() {
        LiveAlertService off = service(false);
        assertEquals(HealthCheckResponse.Status.UP, new AlertLoopLivenessCheck(off).call().getStatus());
        assertEquals(HealthCheckResponse.Status.UP,
                new AlertReadinessCheck(off, telegram(false), NO_DATA, List.of("NQ.FUT")).call().getStatus());
    }

    @Test
    void freshProcess_isLive() {
        assertEquals(HealthCheckResponse.Status.UP, new AlertLoopLivenessCheck(service(true)).call().getStatus());
    }

    @Test
    void alertsOnWithoutTelegram_notReady() {
        assertEquals(HealthCheckResponse.Status.DOWN,
                new AlertReadinessCheck(service(true), telegram(false), NO_DATA, List.of("NQ.FUT")).call().getStatus());
    }
}
