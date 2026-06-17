package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.UptimeCheck;
import com.iptv.saas.repository.*;
import com.iptv.saas.web.ApiException;
import com.iptv.saas.web.Responses;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Service
public class OpsService {
    private final IptvAccountRepository accounts;
    private final UserSessionRepository sessions;
    private final AuditLogRepository auditLogs;
    private final UptimeCheckRepository uptimeChecks;
    private final PaymentTransactionRepository payments;
    private final SupportTicketRepository tickets;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OpsService(
            IptvAccountRepository accounts,
            UserSessionRepository sessions,
            AuditLogRepository auditLogs,
            UptimeCheckRepository uptimeChecks,
            PaymentTransactionRepository payments,
            SupportTicketRepository tickets
    ) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.auditLogs = auditLogs;
        this.uptimeChecks = uptimeChecks;
        this.payments = payments;
        this.tickets = tickets;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> health() {
        boolean hasCapacity = accounts.findByActiveTrueAndDisabledFalse().stream()
                .anyMatch(account -> account.maxStreams <= 0 || account.activeStreams < account.maxStreams);
        Map<String, Object> body = Responses.map();
        body.put("status", hasCapacity ? "ok" : "degraded");
        body.put("database", "ok");
        body.put("iptvCapacityAvailable", hasCapacity);
        body.put("checkedAt", Instant.now());
        return body;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> metrics() {
        int capacity = accounts.findAll().stream().mapToInt(account -> account.maxStreams).sum();
        Map<String, Object> body = Responses.map();
        body.put("activeStreams", sessions.countByStatus(Enums.SessionStatus.ACTIVE));
        body.put("streamCapacity", capacity);
        body.put("healthyAccounts", accounts.countByDisabledFalseAndActiveTrue());
        body.put("pendingPayments", payments.countByStatus(Enums.PaymentStatus.PENDING));
        body.put("openTickets", tickets.countByStatus(Enums.TicketStatus.OPEN));
        body.put("auditLogs", auditLogs.count());
        return body;
    }

    @Transactional
    public UptimeCheck saveCheck(Long id, String name, String url, String method, Boolean enabled) {
        UptimeCheck check = id == null ? new UptimeCheck() : uptimeChecks.findById(id)
                .orElseThrow(() -> ApiException.notFound("Uptime check introuvable"));
        if (name != null && !name.isBlank()) check.name = name;
        if (url != null && !url.isBlank()) check.url = url;
        if (method != null && !method.isBlank()) check.method = method;
        if (enabled != null) check.enabled = enabled;
        return uptimeChecks.save(check);
    }

    @Transactional
    public UptimeCheck runCheck(Long id) {
        UptimeCheck check = uptimeChecks.findById(id).orElseThrow(() -> ApiException.notFound("Uptime check introuvable"));
        Instant start = Instant.now();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(check.url))
                    .timeout(Duration.ofSeconds(8))
                    .method(check.method == null ? "GET" : check.method.toUpperCase(), HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            check.lastLatencyMs = Duration.between(start, Instant.now()).toMillis();
            check.lastCheckedAt = Instant.now();
            check.lastStatus = response.statusCode() >= 200 && response.statusCode() < 400
                    ? Enums.UptimeStatus.OK
                    : Enums.UptimeStatus.DEGRADED;
            check.lastError = response.statusCode() >= 400 ? "HTTP " + response.statusCode() : null;
        } catch (Exception exception) {
            check.lastLatencyMs = Duration.between(start, Instant.now()).toMillis();
            check.lastCheckedAt = Instant.now();
            check.lastStatus = Enums.UptimeStatus.DOWN;
            check.lastError = exception.getMessage();
        }
        return uptimeChecks.save(check);
    }
}
