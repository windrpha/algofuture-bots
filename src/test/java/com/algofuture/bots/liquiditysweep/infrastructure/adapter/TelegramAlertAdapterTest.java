package com.algofuture.bots.liquiditysweep.infrastructure.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramAlertAdapterTest {

    @Test
    void withoutTokenOrChatNothingIsDelivered() {
        assertFalse(new TelegramAlertAdapter(Optional.empty(), Optional.of("1")).deliver("x"));
        assertFalse(new TelegramAlertAdapter(Optional.of("t"), Optional.of(" ")).deliver("x"));
    }

    /**
     * Sends a REAL message to the configured chat, so it only runs with the credentials in the environment:
     * {@code TELEGRAM_BOT_TOKEN=... TELEGRAM_CHAT_ID=... ./gradlew test --tests '*TelegramAlertAdapterTest*'}
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "TELEGRAM_BOT_TOKEN", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "TELEGRAM_CHAT_ID", matches = ".+")
    void realMessageReachesTheChat() {
        var adapter = new TelegramAlertAdapter(Optional.of(System.getenv("TELEGRAM_BOT_TOKEN")),
                Optional.of(System.getenv("TELEGRAM_CHAT_ID")));
        String now = ZonedDateTime.now(ZoneId.of("America/Chicago")).toLocalTime().withNano(0) + " CT";
        assertTrue(adapter.deliver("Alert bot test (" + now + "): if you can read this, Telegram alerts work."),
                "Telegram did not accept the message: see the log above for the HTTP status and reason");
    }
}
