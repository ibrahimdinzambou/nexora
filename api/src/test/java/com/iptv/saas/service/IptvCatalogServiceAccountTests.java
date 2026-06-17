package com.iptv.saas.service;

import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.repository.IptvAccountRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IptvCatalogServiceAccountTests {
    @Test
    void archivesAccountInsteadOfDeletingSessionHistory() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        M3uPlaylistService playlists = mock(M3uPlaylistService.class);
        XtreamCatalogService xtream = mock(XtreamCatalogService.class);
        ProviderMetadataService metadata = mock(ProviderMetadataService.class);
        CatalogImageService images = mock(CatalogImageService.class);
        IptvCatalogService catalog = new IptvCatalogService(accounts, playlists, xtream, metadata, images);

        IptvAccount account = new IptvAccount();
        account.id = 1L;
        account.active = true;
        account.activeStreams = 1;
        account.baseUrl = "https://provider.test";
        account.username = "user";
        account.password = "secret";
        account.playlistUrl = "https://provider.test/list.m3u";
        when(accounts.findById(1L)).thenReturn(Optional.of(account));

        catalog.deleteAccount(1L);

        assertFalse(account.active);
        assertTrue(account.disabled);
        assertEquals(0, account.activeStreams);
        assertEquals("archived", account.lastHealthStatus);
        assertEquals(IptvCatalogService.ARCHIVED_BY_ADMIN, account.disabledReason);
        assertNull(account.baseUrl);
        assertNull(account.username);
        assertNull(account.password);
        assertNull(account.playlistUrl);
        verify(accounts).save(account);
        verify(accounts, never()).delete(account);
        verify(playlists).invalidate(1L);
        verify(xtream).invalidate(1L);
        verify(metadata).invalidate(1L);
    }
}
