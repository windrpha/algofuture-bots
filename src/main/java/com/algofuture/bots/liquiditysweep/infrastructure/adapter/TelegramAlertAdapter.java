package com.algofuture.bots.liquiditysweep.infrastructure.adapter;

import com.algofuture.bots.liquiditysweep.application.port.out.AlertPort;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Sends alerts through the Telegram Bot API ({@code sendMessage}). Token and chat come from the environment
 * (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID) and the token is never logged. Without them alerts are only logged.
 */
@ApplicationScoped
public class TelegramAlertAdapter implements AlertPort {

    private static final Logger LOG = Logger.getLogger(TelegramAlertAdapter.class);

    private final Optional<String> token;
    private final Optional<String> chatId;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public TelegramAlertAdapter(@ConfigProperty(name = "" +
                                        "telegram.bot-token") Optional<String> token,
                                @ConfigProperty(name = "telegram.chat-id") Optional<String> chatId) {
        this.token = token.filter(t -> !t.isBlank());
        this.chatId = chatId.filter(c -> !c.isBlank());
    }

    @Override
    public boolean configured() {
        return token.isPresent() && chatId.isPresent();
    }

    @Override
    public void send(String text) {
        if (!configured()) {
            LOG.warnf("Telegram not configured, alert not sent:%n%s", text);
            return;
        }
        String body = "chat_id=" + URLEncoder.encode(chatId.get(), StandardCharsets.UTF_8)
                + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token.get() + "/sendMessage"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.errorf("Telegram rejected the alert (HTTP %d): %s", response.statusCode(), response.body());
            } else {
                LOG.infof("Alert sent:%n%s", text);
            }
        } catch (java.io.IOException e) {
            LOG.errorf("Telegram unreachable, alert not sent: %s%n%s", e.getMessage(), text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
