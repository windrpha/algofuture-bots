package com.algofuture.bots.liquiditysweep.infrastructure.rest;

import com.algofuture.bots.liquiditysweep.application.port.out.AlertPort;
import com.algofuture.bots.liquiditysweep.application.service.LiveAlertService;
import com.algofuture.shared.infrastructure.rest.CorsHelper;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * GET  /bots/liquidity-sweep/alerts/status → whether live alerts run and what was announced today
 * POST /bots/liquidity-sweep/alerts/test   → sends a test message to the configured Telegram chat
 */
@Path("/liquidity-sweep/alerts")
@Produces(MediaType.TEXT_PLAIN)
public class AlertResource {

    private final LiveAlertService service;
    private final AlertPort alerts;

    public AlertResource(LiveAlertService service, AlertPort alerts) {
        this.service = service;
        this.alerts = alerts;
    }

    @GET
    @Path("/status")
    public Response status() {
        return CorsHelper.apply(Response.ok(service.status())).build();
    }

    @POST
    @Path("/test")
    public Response test() {
        if (!alerts.configured()) {
            return CorsHelper.apply(Response.status(Response.Status.BAD_REQUEST)
                    .entity("Telegram is not configured: set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID")).build();
        }
        alerts.send("Alert bot test: if you can read this, Telegram is connected.");
        return CorsHelper.apply(Response.ok("sent")).build();
    }
}
