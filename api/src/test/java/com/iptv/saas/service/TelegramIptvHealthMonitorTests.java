package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.repository.IptvAccountRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramIptvHealthMonitorTests {
    @Test
    void dailyAuditMarksBrokenAccountsAndSendsTelegramSummary() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        TelegramIptvHealthMonitor monitor = new TelegramIptvHealthMonitor(
                true,
                7,
                90,
                accounts,
                catalog,
                telegram
        );

        IptvAccount working = account(10L, "Source OK");
        IptvAccount empty = account(20L, "Source vide");
        empty.failureCount = 2;

        when(accounts.findByActiveTrueAndDisabledFalse()).thenReturn(List.of(working, empty));
        when(catalog.auditAccount(working)).thenReturn(new IptvCatalogService.AccountAudit(
                working.id,
                working.name,
                "ok",
                8,
                2,
                4,
                true,
                "Catalogue disponible"
        ));
        when(catalog.auditAccount(empty)).thenReturn(new IptvCatalogService.AccountAudit(
                empty.id,
                empty.name,
                "empty-catalog",
                0,
                0,
                0,
                false,
                "Aucun programme exploitable dans ce catalogue"
        ));
        when(telegram.configured()).thenReturn(true);

        TelegramIptvHealthMonitor.AuditSummary summary = monitor.runDailyAudit();

        assertEquals(2, summary.totalAccounts());
        assertEquals(1, summary.alerts());
        assertEquals(1, summary.emptyCatalogs());
        assertEquals(10, summary.totalContent());
        assertEquals("ok", working.lastHealthStatus);
        assertEquals(0, working.failureCount);
        assertEquals("empty-catalog", empty.lastHealthStatus);
        assertEquals(3, empty.failureCount);
        verify(accounts).save(working);
        verify(accounts).save(empty);
        verify(telegram).send(eq("Audit IPTV quotidien"), contains("Catalogues vides: 1"));
        verify(telegram).send(eq("Audit IPTV quotidien"), contains("Source vide"));
    }

    private IptvAccount account(Long id, String name) {
        IptvAccount account = new IptvAccount();
        account.id = id;
        account.name = name;
        account.accountType = Enums.IptvAccountType.M3U;
        account.active = true;
        account.disabled = false;
        account.maxStreams = 2;
        account.playlistUrl = "https://example.test/list.m3u";
        return account;
    }
}
