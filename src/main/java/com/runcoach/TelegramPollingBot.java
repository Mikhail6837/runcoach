package com.runcoach;

import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class TelegramPollingBot {
    private final RestClient telegram = RestClient.create("https://api.telegram.org");
    private final AtomicBoolean running = new AtomicBoolean();
    private final String token;
    private final String miniAppUrl;
    private volatile long nextUpdateId;

    public TelegramPollingBot(
            @Value("${runcoach.telegram.token:}") String token,
            @Value("${runcoach.telegram.mini-app-url:http://localhost:8080}") String miniAppUrl) {
        this.token = token;
        this.miniAppUrl = miniAppUrl;
    }

    @jakarta.annotation.PostConstruct
    public void start() {
        if (token == null || token.isBlank()) return;
        URI uri = URI.create(miniAppUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalStateException("RUNCOACH_MINI_APP_URL must be a public HTTPS address.");
        }
        running.set(true);
        Thread poller = new Thread(this::pollUpdates, "runcoach-telegram-poller");
        poller.setDaemon(true);
        poller.start();
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        running.set(false);
    }

    private void pollUpdates() {
        while (running.get()) {
            try {
                JsonNode response = telegram.post()
                        .uri(uri -> uri.path("/bot" + token + "/getUpdates")
                                .queryParam("timeout", 25)
                                .queryParam("offset", nextUpdateId)
                                .build())
                        .retrieve()
                        .body(JsonNode.class);
                if (response == null || !response.path("ok").asBoolean()) continue;
                for (JsonNode update : response.path("result")) {
                    nextUpdateId = update.path("update_id").asLong() + 1;
                    JsonNode message = update.path("message");
                    String text = message.path("text").asText("");
                    if (text.equals("/start") || text.startsWith("/start ") || text.equals("/app")) {
                        sendLaunchButton(message.path("chat").path("id").asLong());
                    }
                }
            } catch (Exception error) {
                System.err.println("Telegram polling error: " + error.getMessage());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void sendLaunchButton(long chatId) {
        Map<String, Object> button = Map.of(
                "text", "Открыть RunCoach",
                "web_app", Map.of("url", miniAppUrl));
        Map<String, Object> replyMarkup = Map.of("inline_keyboard", List.of(List.of(button)));
        Map<String, Object> body = Map.of(
                "chat_id", chatId,
                "text", "Добро пожаловать в RunCoach! Открой приложение, чтобы посмотреть план тренировок.",
                "reply_markup", replyMarkup);

        telegram.post()
                .uri("/bot" + token + "/sendMessage")
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
