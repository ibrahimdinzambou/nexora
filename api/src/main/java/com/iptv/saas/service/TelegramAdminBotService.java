package com.iptv.saas.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.domain.Enums;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TelegramAdminBotService {
    private static final Logger log = LoggerFactory.getLogger(TelegramAdminBotService.class);

    private final boolean panelEnabled;
    private final String botToken;
    private final String primaryChatId;
    private final Set<String> allowedChatIds;
    private final Set<String> readOnlyChatIds;
    private final Map<String, Enums.UserRole> chatRoles;
    private final long pollIntervalMs;
    private final long pollInitialDelayMs;
    private final TelegramAdminCommandService commands;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private long offset = 0;
    private Instant lastPollAt;

    public TelegramAdminBotService(
            @Value("${app.telegram.admin.enabled:false}") boolean panelEnabled,
            @Value("${app.telegram.admin.bot-token:}") String botToken,
            @Value("${app.telegram.admin.chat-id:}") String primaryChatId,
            @Value("${app.telegram.admin.allowed-chat-ids:}") String allowedChatIds,
            @Value("${app.telegram.admin.readonly-chat-ids:}") String readOnlyChatIds,
            @Value("${app.telegram.admin.chat-roles:}") String chatRoles,
            @Value("${app.telegram.admin.poll-interval-ms:3000}") long pollIntervalMs,
            @Value("${app.telegram.admin.poll-initial-delay-ms:5000}") long pollInitialDelayMs,
            TelegramAdminCommandService commands,
            ObjectMapper mapper
    ) {
        this.panelEnabled = panelEnabled;
        this.botToken = botToken;
        this.primaryChatId = primaryChatId;
        this.allowedChatIds = chatSet(allowedChatIds, primaryChatId);
        this.readOnlyChatIds = chatSet(readOnlyChatIds, null);
        this.chatRoles = roleMap(chatRoles, this.allowedChatIds);
        this.pollIntervalMs = pollIntervalMs;
        this.pollInitialDelayMs = pollInitialDelayMs;
        this.commands = commands;
        this.mapper = mapper;
    }

    @PostConstruct
    void logStartupStatus() {
        if (!panelEnabled) {
            log.info("Telegram admin bot disabled: app.telegram.admin.enabled=false");
            return;
        }
        if (!configured()) {
            log.info("Telegram admin bot enabled but incomplete: token={}, allowedChats={}",
                    hasToken() ? "configured" : "missing",
                    allowedChatIds.size());
            return;
        }
        log.info("Telegram admin bot enabled: polling every {} ms, allowedChats={}, readOnlyChats={}, roles={}",
                pollIntervalMs,
                allowedChatIds.size(),
                readOnlyChatIds.size(),
                chatRoles.values().stream().map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    @Scheduled(fixedDelayString = "${app.telegram.admin.poll-interval-ms:3000}", initialDelayString = "${app.telegram.admin.poll-initial-delay-ms:5000}")
    public void poll() {
        if (!configured()) {
            return;
        }
        try {
            lastPollAt = Instant.now();
            HttpResponse<String> response = client.send(getUpdatesRequest(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.info("Telegram admin polling rejected with HTTP {}", response.statusCode());
                return;
            }
            JsonNode root = mapper.readTree(response.body());
            if (!root.path("ok").asBoolean(false)) {
                log.info("Telegram admin polling returned ok=false");
                return;
            }
            for (JsonNode update : root.path("result")) {
                offset = Math.max(offset, update.path("update_id").asLong() + 1);
                handleUpdate(update);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            log.info("Telegram admin polling failed: {}", rootMessage(exception));
        }
    }

    public boolean configured() {
        return panelEnabled
                && hasToken()
                && !allowedChatIds.isEmpty();
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", panelEnabled);
        status.put("configured", configured());
        status.put("botToken", hasToken() ? "***configure***" : "");
        status.put("primaryChatId", maskChatId(primaryChatId));
        status.put("allowedChatIds", maskedSet(allowedChatIds));
        status.put("readOnlyChatIds", maskedSet(readOnlyChatIds));
        status.put("chatRoles", maskedRoleMap());
        status.put("polling", configured());
        status.put("pollIntervalMs", pollIntervalMs);
        status.put("pollInitialDelayMs", pollInitialDelayMs);
        status.put("lastPollAt", lastPollAt == null ? "-" : lastPollAt.toString());
        status.put("endpoint", "api.telegram.org");
        return status;
    }

    public TelegramAlertService.DeliveryResult test() {
        if (!configured()) {
            return new TelegramAlertService.DeliveryResult(false, "Bot admin Telegram desactive ou incomplet");
        }
        String chatId = primaryChatId != null && !primaryChatId.isBlank()
                ? primaryChatId.strip()
                : allowedChatIds.iterator().next();
        try {
            TelegramAdminCommandService.Response response = new TelegramAdminCommandService.Response(
                    "Test bot admin Nexora\n\nLe bot admin repond correctement. Utilisez /whoami ou /admin_status pour verifier le contexte.",
                    List.of()
            );
            HttpResponse<String> answer = client.send(
                    formRequest("sendMessage", messagePayload(chatId, response), Duration.ofSeconds(30)),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (answer.statusCode() >= 200 && answer.statusCode() < 300) {
                return new TelegramAlertService.DeliveryResult(true, "Message de test envoye par le bot admin");
            }
            return new TelegramAlertService.DeliveryResult(false, "Telegram admin a repondu HTTP " + answer.statusCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new TelegramAlertService.DeliveryResult(false, "Test Telegram admin interrompu");
        } catch (Exception exception) {
            return new TelegramAlertService.DeliveryResult(false, rootMessage(exception));
        }
    }

    private void handleUpdate(JsonNode update) {
        if (update.has("callback_query")) {
            handleCallback(update.path("callback_query"));
            return;
        }
        JsonNode message = update.path("message");
        if (message.isMissingNode() || !message.hasNonNull("text")) {
            return;
        }
        String chatId = message.path("chat").path("id").asText();
        if (!allowed(chatId)) {
            log.info("Telegram admin command ignored from unauthorized chat {}", chatId);
            return;
        }
        TelegramAdminCommandService.Response reply = commands.handle(
                message.path("text").asText(),
                context(chatId)
        );
        sendMessage(chatId, reply);
    }

    private void handleCallback(JsonNode callback) {
        String callbackId = callback.path("id").asText();
        JsonNode message = callback.path("message");
        String chatId = message.path("chat").path("id").asText();
        if (!allowed(chatId)) {
            answerCallback(callbackId, "Chat non autorise");
            log.info("Telegram admin callback ignored from unauthorized chat {}", chatId);
            return;
        }
        TelegramAdminCommandService.Response reply = commands.handleCallback(
                callback.path("data").asText(),
                context(chatId)
        );
        answerCallback(callbackId, "OK");
        sendMessage(chatId, reply);
    }

    private boolean allowed(String chatId) {
        return chatId != null && allowedChatIds.contains(chatId);
    }

    private boolean readOnly(String chatId) {
        return readOnlyChatIds.contains(chatId);
    }

    private Enums.UserRole role(String chatId) {
        return chatRoles.getOrDefault(chatId, Enums.UserRole.SUPER_ADMIN);
    }

    private TelegramAdminCommandService.ChatContext context(String chatId) {
        return new TelegramAdminCommandService.ChatContext(chatId, role(chatId), readOnly(chatId), status());
    }

    private HttpRequest getUpdatesRequest() {
        String payload = "offset=" + offset
                + "&timeout=0"
                + "&allowed_updates=" + encode("[\"message\",\"callback_query\"]");
        return formRequest("getUpdates", payload, Duration.ofSeconds(30));
    }

    private void sendMessage(String chatId, TelegramAdminCommandService.Response response) {
        try {
            String payload = messagePayload(chatId, response);
            client.sendAsync(formRequest("sendMessage", payload, Duration.ofSeconds(30)), HttpResponse.BodyHandlers.ofString())
                    .thenAccept(answer -> {
                        if (answer.statusCode() < 200 || answer.statusCode() >= 300) {
                            log.info("Telegram admin reply rejected with HTTP {}", answer.statusCode());
                        }
                    })
                    .exceptionally(exception -> {
                        log.info("Telegram admin reply failed: {}", rootMessage(exception));
                        return null;
                    });
        } catch (Exception exception) {
            log.info("Telegram admin reply skipped: {}", rootMessage(exception));
        }
    }

    private void answerCallback(String callbackId, String text) {
        if (callbackId == null || callbackId.isBlank()) {
            return;
        }
        try {
            String payload = "callback_query_id=" + encode(callbackId)
                    + "&text=" + encode(text == null ? "" : text);
            client.sendAsync(formRequest("answerCallbackQuery", payload, Duration.ofSeconds(15)), HttpResponse.BodyHandlers.ofString());
        } catch (Exception exception) {
            log.info("Telegram callback answer skipped: {}", rootMessage(exception));
        }
    }

    private HttpRequest formRequest(String method, String payload, Duration timeout) {
        return HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot" + botToken + "/" + method))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
    }

    private String keyboard(List<List<TelegramAdminCommandService.Button>> rows) throws JsonProcessingException {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        List<List<Map<String, String>>> inlineKeyboard = rows.stream()
                .map(row -> row.stream()
                        .map(button -> {
                            Map<String, String> payload = new LinkedHashMap<>();
                            payload.put("text", button.text());
                            payload.put("callback_data", button.callbackData());
                            return payload;
                        })
                        .toList())
                .toList();
        return mapper.writeValueAsString(Map.of("inline_keyboard", inlineKeyboard));
    }

    private Set<String> chatSet(String configured, String fallback) {
        Set<String> values = Arrays.stream(String.valueOf(configured == null ? "" : configured).split("[,\\s]+"))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (values.isEmpty() && fallback != null && !fallback.isBlank()) {
            values.add(fallback.strip());
        }
        return Set.copyOf(values);
    }

    private Map<String, Enums.UserRole> roleMap(String configured, Set<String> allowedChatIds) {
        Map<String, Enums.UserRole> roles = new LinkedHashMap<>();
        Arrays.stream(String.valueOf(configured == null ? "" : configured).split("[,\\s]+"))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .forEach(value -> {
                    String[] parts = value.split("[:=]", 2);
                    if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                        roles.put(parts[0].strip(), parseRole(parts[1].strip()));
                    }
                });
        allowedChatIds.forEach(chatId -> roles.putIfAbsent(chatId, Enums.UserRole.SUPER_ADMIN));
        return Map.copyOf(roles);
    }

    private Enums.UserRole parseRole(String value) {
        try {
            return Enums.UserRole.valueOf(value.toUpperCase());
        } catch (RuntimeException exception) {
            log.info("Telegram admin chat role ignored: {}", value);
            return Enums.UserRole.SUPER_ADMIN;
        }
    }

    private String messagePayload(String chatId, TelegramAdminCommandService.Response response) throws JsonProcessingException {
        String payload = "chat_id=" + encode(chatId)
                + "&text=" + encode(markdown(truncate(response.text())))
                + "&parse_mode=MarkdownV2"
                + "&disable_web_page_preview=true";
        String keyboard = keyboard(response.keyboard());
        if (keyboard != null) {
            payload += "&reply_markup=" + encode(keyboard);
        }
        return payload;
    }

    private String truncate(String value) {
        return value.length() <= 3900 ? value : value.substring(0, 3890) + "\n...";
    }

    private String markdown(String value) {
        String[] parts = String.valueOf(value == null ? "" : value).split("\\R", 2);
        String title = escapeMarkdown(parts.length == 0 ? "" : parts[0]);
        if (parts.length == 1) {
            return "*" + title + "*";
        }
        return "*" + title + "*\n" + escapeMarkdown(parts[1]);
    }

    private String escapeMarkdown(String value) {
        return String.valueOf(value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("~", "\\~")
                .replace("`", "\\`")
                .replace(">", "\\>")
                .replace("#", "\\#")
                .replace("+", "\\+")
                .replace("-", "\\-")
                .replace("=", "\\=")
                .replace("|", "\\|")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace(".", "\\.")
                .replace("!", "\\!");
    }

    private boolean hasToken() {
        return botToken != null && !botToken.isBlank();
    }

    private List<String> maskedSet(Set<String> chatIds) {
        return chatIds.stream().map(this::maskChatId).toList();
    }

    private Map<String, String> maskedRoleMap() {
        Map<String, String> masked = new LinkedHashMap<>();
        chatRoles.forEach((chatId, role) -> masked.put(maskChatId(chatId), role.name()));
        return masked;
    }

    private String maskChatId(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return "";
        }
        String stripped = chatId.strip();
        int visible = Math.min(4, stripped.length());
        return "***" + stripped.substring(stripped.length() - visible);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String rootMessage(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
