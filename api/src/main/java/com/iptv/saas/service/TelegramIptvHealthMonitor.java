package com.iptv.saas.service;

import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.repository.IptvAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TelegramIptvHealthMonitor {
    private final boolean enabled;
    private final int expiresSoonDays;
    private final int saturationThreshold;
    private final IptvAccountRepository accounts;
    private final IptvCatalogService catalog;
    private final TelegramAlertService telegram;

    public TelegramIptvHealthMonitor(
            @Value("${app.telegram.alerts.iptv-alerts-enabled:true}") boolean enabled,
            @Value("${app.telegram.alerts.iptv-expiry-alert-days:7}") int expiresSoonDays,
            @Value("${app.telegram.alerts.iptv-saturation-threshold-percent:90}") int saturationThreshold,
            IptvAccountRepository accounts,
            IptvCatalogService catalog,
            TelegramAlertService telegram
    ) {
        this.enabled = enabled;
        this.expiresSoonDays = Math.max(1, expiresSoonDays);
        this.saturationThreshold = Math.max(1, Math.min(100, saturationThreshold));
        this.accounts = accounts;
        this.catalog = catalog;
        this.telegram = telegram;
    }

    @Scheduled(
            cron = "${app.telegram.alerts.iptv-daily-cron:0 0 0 * * *}",
            zone = "${app.telegram.alerts.iptv-daily-zone:Africa/Lagos}"
    )
    @Transactional
    public void scheduledDailyAudit() {
        notifyTelegram(auditAccounts());
    }

    @Transactional
    public AuditSummary runDailyAudit() {
        AuditSummary summary = auditAccounts();
        notifyTelegram(summary);
        return summary;
    }

    private void notifyTelegram(AuditSummary summary) {
        if (enabled && telegram.configured()) {
            telegram.send("Audit IPTV quotidien", telegramBody(summary));
        }
    }

    private AuditSummary auditAccounts() {
        List<AuditLine> lines = new ArrayList<>();
        Map<String, Integer> statuses = new LinkedHashMap<>();
        int totalContent = 0;

        for (IptvAccount account : accounts.findByActiveTrueAndDisabledFalse()) {
            IptvCatalogService.AccountAudit audit = catalog.auditAccount(account);
            String status = operationalStatus(account, audit);
            boolean alert = alertStatus(status);

            account.lastHealthStatus = status;
            account.failureCount = alert ? account.failureCount + 1 : 0;
            accounts.save(account);

            statuses.merge(status, 1, Integer::sum);
            totalContent += audit.contentCount();
            lines.add(new AuditLine(
                    account.id,
                    account.name,
                    status,
                    alert,
                    audit.entries(),
                    audit.series(),
                    audit.categories(),
                    audit.message()
            ));
        }

        long alerts = lines.stream().filter(AuditLine::alert).count();
        long emptyCatalogs = lines.stream().filter(line -> "empty-catalog".equals(line.status())).count();
        return new AuditSummary(
                Instant.now(),
                lines.size(),
                (int) alerts,
                (int) emptyCatalogs,
                totalContent,
                statuses,
                List.copyOf(lines)
        );
    }

    private String operationalStatus(IptvAccount account, IptvCatalogService.AccountAudit audit) {
        if (!audit.displayable()) {
            return audit.status();
        }
        if (account.expiresAt != null && !account.expiresAt.isAfter(Instant.now().plus(Duration.ofDays(expiresSoonDays)))) {
            return "expires-soon";
        }
        if (account.maxStreams > 0) {
            int usage = Math.round(account.activeStreams * 100f / account.maxStreams);
            if (usage >= saturationThreshold) {
                return "saturated";
            }
        }
        return audit.status();
    }

    private boolean alertStatus(String status) {
        return List.of("expired", "misconfigured", "empty-catalog", "catalog-unavailable", "disabled")
                .contains(status);
    }

    private String telegramBody(AuditSummary summary) {
        StringBuilder body = new StringBuilder()
                .append("Date: ").append(summary.ranAt()).append('\n')
                .append("Comptes testes: ").append(summary.totalAccounts()).append('\n')
                .append("Alertes: ").append(summary.alerts()).append('\n')
                .append("Catalogues vides: ").append(summary.emptyCatalogs()).append('\n')
                .append("Contenus detectes: ").append(summary.totalContent()).append("\n\n");

        summary.statuses().forEach((status, count) ->
                body.append("- ").append(status).append(": ").append(count).append('\n'));

        List<AuditLine> alertLines = summary.lines().stream()
                .filter(AuditLine::alert)
                .limit(20)
                .toList();
        if (!alertLines.isEmpty()) {
            body.append("\nA corriger:\n");
            alertLines.forEach(line -> body
                    .append("#").append(line.accountId()).append(" ")
                    .append(line.accountName()).append(" - ")
                    .append(line.status()).append(" - ")
                    .append(line.message()).append('\n'));
        }
        if (summary.lines().stream().filter(AuditLine::alert).count() > alertLines.size()) {
            body.append("... autres comptes en alerte visibles dans la console.");
        }
        return body.toString();
    }

    public record AuditSummary(
            Instant ranAt,
            int totalAccounts,
            int alerts,
            int emptyCatalogs,
            int totalContent,
            Map<String, Integer> statuses,
            List<AuditLine> lines
    ) {
    }

    public record AuditLine(
            Long accountId,
            String accountName,
            String status,
            boolean alert,
            int entries,
            int series,
            int categories,
            String message
    ) {
    }
}
