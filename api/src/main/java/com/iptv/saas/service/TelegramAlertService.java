package com.iptv.saas.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TelegramAlertService {
    private static final Logger log = LoggerFactory.getLogger(TelegramAlertService.class);

    private final boolean enabled;
    private final String botToken;
    private final String chatId;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public TelegramAlertService(
            @Value("${app.telegram.alerts.enabled:false}") boolean enabled,
            @Value("${app.telegram.alerts.bot-token:}") String botToken,
            @Value("${app.telegram.alerts.chat-id:}") String chatId,
            ObjectMapper mapper
    ) {
        this.enabled = enabled;
        this.botToken = botToken;
        this.chatId = chatId;
        this.mapper = mapper;
    }

    public void send(String title, String body) {
        send(title, body, List.of());
    }

    public void send(String title, String body, List<List<Action>> actions) {
        if (!configured()) {
            return;
        }
        try {
            client.sendAsync(request(title, body, actions), HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            log.info("Telegram alert rejected with HTTP {}", response.statusCode());
                        }
                    })
                    .exceptionally(exception -> {
                        log.info("Telegram alert failed: {}", exception.getMessage());
                        return null;
                    });
        } catch (Exception exception) {
            log.info("Telegram alert skipped: {}", exception.getMessage());
        }
    }

    public DeliveryResult test() {
        if (!configured()) {
            return new DeliveryResult(false, "Telegram est desactive ou incomplet");
        }
        try {
            HttpResponse<String> response = client.send(
                    request("Test Nexora", "La connexion au bot Telegram fonctionne correctement.", List.of()),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new DeliveryResult(true, "Message de test envoye au chat configure");
            }
            return new DeliveryResult(false, "Telegram a repondu HTTP " + response.statusCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new DeliveryResult(false, "Test Telegram interrompu");
        } catch (Exception exception) {
            return new DeliveryResult(false, rootMessage(exception));
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("configured", configured());
        status.put("botToken", botToken == null || botToken.isBlank() ? "" : "***configure***");
        status.put("chatId", maskChatId());
        status.put("endpoint", "api.telegram.org");
        return status;
    }

    public boolean configured() {
        return enabled && botToken != null && !botToken.isBlank() && chatId != null && !chatId.isBlank();
    }

    private HttpRequest request(String title, String body, List<List<Action>> actions) throws JsonProcessingException {
        String text = title + "\n\n" + body;
        String payload = "chat_id=" + encode(chatId) + "&text=" + encode(text);
        String keyboard = keyboard(actions);
        if (keyboard != null) {
            payload += "&reply_markup=" + encode(keyboard);
        }
        return HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
    }

    private String maskChatId() {
        if (chatId == null || chatId.isBlank()) {
            return "";
        }
        int visible = Math.min(4, chatId.length());
        return "***" + chatId.substring(chatId.length() - visible);
    }

    private String rootMessage(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String keyboard(List<List<Action>> actions) throws JsonProcessingException {
        if (actions == null || actions.isEmpty()) {
            return null;
        }
        List<List<Map<String, String>>> inlineKeyboard = actions.stream()
                .map(row -> row.stream()
                        .map(action -> Map.of(
                                "text", action.text(),
                                "callback_data", action.callbackData()
                        ))
                        .toList())
                .toList();
        return mapper.writeValueAsString(Map.of("inline_keyboard", inlineKeyboard));
    }

    public record Action(String text, String callbackData) {
    }

    public record DeliveryResult(boolean success, String message) {
    }
}
