package com.iptv.saas.service;

import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.repository.IptvAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TelegramIptvHealthMonitor {
    private final boolean enabled;
    private final int expiresSoonDays;
    private final int saturationThreshold;
    private final IptvAccountRepository accounts;
    private final IptvCatalogService catalog;
    private final TelegramAlertService telegram;
    private final Map<Long, String> lastAlertState = new ConcurrentHashMap<>();

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

    @Scheduled(fixedDelayString = "${app.telegram.alerts.iptv-alert-interval-ms:300000}", initialDelayString = "${app.telegram.alerts.iptv-alert-initial-delay-ms:30000}")
    @Transactional
    public void checkAccounts() {
        if (!enabled || !telegram.configured()) {
            return;
        }
        for (IptvAccount account : accounts.findByActiveTrueAndDisabledFalse()) {
            String state = state(account);
            if ("ok".equals(state)) {
                lastAlertState.remove(account.id);
                continue;
            }
            if (state.equals(lastAlertState.get(account.id))) {
                continue;
            }
            lastAlertState.put(account.id, state);
            account.lastHealthStatus = catalog.health(account);
            accounts.save(account);
            telegram.send(
                    "Compte IPTV en alerte",
                    "#%d %s\n%s\nCharge: %d/%s"
                            .formatted(
                                    account.id,
                                    account.name,
                                    state,
                                    account.activeStreams,
                                    account.maxStreams <= 0 ? "infini" : String.valueOf(account.maxStreams)
                            ),
                    List.of(List.of(
                            new TelegramAlertService.Action("Tester", "test_account:" + account.id),
                            new TelegramAlertService.Action("Desactiver", "confirm:disable_account:" + account.id)
                    ))
            );
        }
    }

    private String state(IptvAccount account) {
        String health = catalog.health(account);
        if (!"ok".equalsIgnoreCase(health) && !"saturated".equalsIgnoreCase(health)) {
            return "Sante: " + health;
        }
        if (account.expiresAt != null && !account.expiresAt.isAfter(Instant.now().plus(Duration.ofDays(expiresSoonDays)))) {
            return "Expiration proche: " + account.expiresAt;
        }
        if (account.maxStreams > 0) {
            int usage = Math.round(account.activeStreams * 100f / account.maxStreams);
            if (usage >= saturationThreshold) {
                return "Saturation: " + usage + "%";
            }
        }
        return "ok";
    }
}
