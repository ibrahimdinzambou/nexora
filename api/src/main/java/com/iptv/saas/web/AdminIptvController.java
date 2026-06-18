package com.iptv.saas.web;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.repository.IptvAccountRepository;
import com.iptv.saas.repository.UserSessionRepository;
import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.IptvCatalogService;
import com.iptv.saas.service.StreamingService;
import com.iptv.saas.service.TelegramIptvHealthMonitor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/admin")
public class AdminIptvController {
    private final IptvAccountRepository accounts;
    private final UserSessionRepository sessions;
    private final IptvCatalogService catalog;
    private final StreamingService streaming;
    private final TelegramIptvHealthMonitor healthMonitor;

    public AdminIptvController(
            IptvAccountRepository accounts,
            UserSessionRepository sessions,
            IptvCatalogService catalog,
            StreamingService streaming,
            TelegramIptvHealthMonitor healthMonitor
    ) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.catalog = catalog;
        this.streaming = streaming;
        this.healthMonitor = healthMonitor;
    }

    @GetMapping("/stats")
    public Object stats() {
        var body = Responses.map();
        body.put("accounts", accounts.count());
        body.put("sessions", sessions.count());
        body.put("activeSessions", sessions.countByStatus(Enums.SessionStatus.ACTIVE));
        return Responses.ok(body);
    }

    @GetMapping("/accounts")
    public Object accounts() {
        return Responses.ok(this.accounts.findAll().stream()
                .filter(account -> !IptvCatalogService.ARCHIVED_BY_ADMIN.equals(account.disabledReason))
                .map(ApiMappers::iptvAccount)
                .toList());
    }

    @PostMapping("/accounts")
    public Object createAccount(@Valid @RequestBody AccountRequest request) {
        return Responses.ok(ApiMappers.iptvAccount(catalog.saveAccount(
                null,
                request.name(),
                request.type(),
                request.baseUrl(),
                request.username(),
                request.password(),
                request.playlistUrl(),
                request.maxStreams(),
                request.active(),
                request.expiresAt()
        )));
    }

    @PutMapping("/accounts/{id}")
    public Object updateAccount(@PathVariable Long id, @RequestBody AccountRequest request) {
        return Responses.ok(ApiMappers.iptvAccount(catalog.saveAccount(
                id,
                request.name(),
                request.type(),
                request.baseUrl(),
                request.username(),
                request.password(),
                request.playlistUrl(),
                request.maxStreams(),
                request.active(),
                request.expiresAt()
        )));
    }

    @DeleteMapping("/accounts/{id}")
    public Object deleteAccount(@PathVariable Long id) {
        var account = accounts.findById(id)
                .orElseThrow(() -> ApiException.notFound("Compte IPTV introuvable"));
        sessions.findByIptvAccountAndStatus(account, Enums.SessionStatus.ACTIVE)
                .forEach(session -> streaming.closeByAdmin(SecurityUtils.currentUser(), session.id));
        catalog.deleteAccount(id);
        return Responses.message("Compte IPTV archive");
    }

    @GetMapping("/accounts/{id}/status")
    public Object accountStatus(@PathVariable Long id) {
        var account = accounts.findById(id).orElseThrow(() -> ApiException.notFound("Compte IPTV introuvable"));
        var body = Responses.map();
        var audit = catalog.auditAccount(account);
        account.lastHealthStatus = audit.status();
        account.failureCount = audit.displayable() ? 0 : account.failureCount + 1;
        accounts.save(account);
        body.put("local", ApiMappers.iptvAccount(account));
        body.put("remote", audit.status());
        body.put("audit", audit);
        return Responses.ok(body);
    }

    @PostMapping("/accounts/audit")
    public Object auditAccounts() {
        return Responses.ok(healthMonitor.runDailyAudit());
    }

    @PostMapping("/accounts/{id}/sync-limits")
    public Object syncLimits(@PathVariable Long id) {
        return Responses.ok(catalog.syncLimits(id));
    }

    @PostMapping("/accounts/{id}/refresh-cache")
    public Object refreshCache(@PathVariable Long id) {
        return Responses.ok(catalog.refreshCache(id));
    }

    @GetMapping("/sessions")
    public Object sessions() {
        return Responses.ok(this.sessions.findAll().stream().map(ApiMappers::session).toList());
    }

    @DeleteMapping("/sessions/{id}")
    public Object closeSession(@PathVariable Long id) {
        streaming.closeByAdmin(SecurityUtils.currentUser(), id);
        return Responses.message("Session fermee");
    }

    public record AccountRequest(
            @NotBlank String name,
            Enums.IptvAccountType type,
            String baseUrl,
            String username,
            String password,
            String playlistUrl,
            Integer maxStreams,
            Boolean active,
            Instant expiresAt
    ) {
    }
}
