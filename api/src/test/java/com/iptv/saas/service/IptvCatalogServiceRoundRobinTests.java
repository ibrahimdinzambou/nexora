package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.repository.IptvAccountRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IptvCatalogServiceRoundRobinTests {
    @Test
    void deduplicatesCatalogAndRotatesBetweenEquivalentSources() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        M3uPlaylistService playlists = mock(M3uPlaylistService.class);
        XtreamCatalogService xtream = mock(XtreamCatalogService.class);
        ProviderMetadataService metadata = mock(ProviderMetadataService.class);
        CatalogImageService images = mock(CatalogImageService.class);
        IptvCatalogService catalog = new IptvCatalogService(accounts, playlists, xtream, metadata, images);

        IptvAccount first = account(11L, "Source A");
        IptvAccount second = account(22L, "Source B");
        M3uPlaylistService parser = new M3uPlaylistService(
                900,
                2_000_000,
                "target/test-round-robin-cache",
                168,
                30
        );
        M3uPlaylistService.Playlist firstPlaylist = parser.parse(first, playlist(
                "http://first.test/live/user/pass/101.ts",
                "http://first.test/movie/user/pass/201.mp4"
        ));
        M3uPlaylistService.Playlist secondPlaylist = parser.parse(second, playlist(
                "http://second.test/live/user/pass/901.ts",
                "http://second.test/movie/user/pass/902.mp4"
        ));

        when(accounts.findByActiveTrueAndDisabledFalse()).thenReturn(List.of(first, second));
        when(accounts.findById(first.id)).thenReturn(Optional.of(first));
        when(playlists.load(first)).thenReturn(firstPlaylist);
        when(playlists.load(second)).thenReturn(secondPlaylist);

        List<Map<String, Object>> categories = catalog.categories(null);
        List<Map<String, Object>> live = catalog.items("live", null, null, "default", 0);
        List<Map<String, Object>> movies = catalog.items("movie", null, null, "default", 0);

        assertEquals(2, categories.size());
        assertEquals(1, live.size());
        assertEquals(1, movies.size());
        assertNotEquals(firstPlaylist.entries().get(0).categoryId(), live.get(0).get("categoryId"));

        String itemId = String.valueOf(live.get(0).get("id"));
        IptvCatalogService.StreamSelection firstSelection = catalog.selectStream("live", itemId);
        first.activeStreams = 1;
        IptvCatalogService.StreamSelection secondSelection = catalog.selectStream("live", itemId);
        IptvCatalogService.StreamSelection failoverSelection = catalog.selectStream("live", itemId, first.id);

        assertEquals(first.id, firstSelection.account().id);
        assertEquals(second.id, secondSelection.account().id);
        assertEquals("http://first.test/live/user/pass/101.ts", firstSelection.streamUrl());
        assertEquals("http://second.test/live/user/pass/901.ts", secondSelection.streamUrl());
        assertEquals(second.id, failoverSelection.account().id);
    }

    @Test
    void servesCachedSourcesWithoutBlockingOnUncachedAccounts() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        M3uPlaylistService playlists = mock(M3uPlaylistService.class);
        XtreamCatalogService xtream = mock(XtreamCatalogService.class);
        ProviderMetadataService metadata = mock(ProviderMetadataService.class);
        CatalogImageService images = mock(CatalogImageService.class);
        IptvCatalogService catalog = new IptvCatalogService(accounts, playlists, xtream, metadata, images);

        IptvAccount cached = account(11L, "Source en cache");
        IptvAccount uncached = account(22L, "Source sans cache");
        M3uPlaylistService parser = new M3uPlaylistService(
                900,
                2_000_000,
                "target/test-cached-source",
                168,
                30
        );
        M3uPlaylistService.Playlist cachedPlaylist = parser.parse(cached, playlist(
                "http://cached.test/live/user/pass/101.ts",
                "http://cached.test/movie/user/pass/201.mp4"
        ));

        when(accounts.findByActiveTrueAndDisabledFalse()).thenReturn(List.of(cached, uncached));
        when(playlists.hasReusableCache(cached.id)).thenReturn(true);
        when(playlists.hasReusableCache(uncached.id)).thenReturn(false);
        when(playlists.load(cached)).thenReturn(cachedPlaylist);
        when(playlists.refreshInBackground(uncached)).thenReturn(true);

        List<Map<String, Object>> live = catalog.items("live", null, null, "default", 24);

        assertEquals(1, live.size());
        verify(playlists, never()).load(uncached);
        verify(playlists).refreshInBackground(uncached);
    }

    @Test
    void prefersEquivalentSourcesWithoutRecentStreamFailures() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        M3uPlaylistService playlists = mock(M3uPlaylistService.class);
        XtreamCatalogService xtream = mock(XtreamCatalogService.class);
        ProviderMetadataService metadata = mock(ProviderMetadataService.class);
        CatalogImageService images = mock(CatalogImageService.class);
        IptvCatalogService catalog = new IptvCatalogService(accounts, playlists, xtream, metadata, images);

        IptvAccount failed = account(11L, "Source en echec");
        failed.lastHealthStatus = "stream-failed";
        IptvAccount healthy = account(22L, "Source saine");
        M3uPlaylistService parser = new M3uPlaylistService(
                900,
                2_000_000,
                "target/test-stream-failed-source",
                168,
                30
        );
        M3uPlaylistService.Playlist failedPlaylist = parser.parse(failed, playlist(
                "http://failed.test/live/user/pass/101.ts",
                "http://failed.test/movie/user/pass/201.mp4"
        ));
        M3uPlaylistService.Playlist healthyPlaylist = parser.parse(healthy, playlist(
                "http://healthy.test/live/user/pass/901.ts",
                "http://healthy.test/movie/user/pass/902.mp4"
        ));

        when(accounts.findByActiveTrueAndDisabledFalse()).thenReturn(List.of(failed, healthy));
        when(accounts.findById(failed.id)).thenReturn(Optional.of(failed));
        when(playlists.load(failed)).thenReturn(failedPlaylist);
        when(playlists.load(healthy)).thenReturn(healthyPlaylist);

        IptvCatalogService.StreamSelection selection = catalog.selectStream(
                "live",
                failedPlaylist.entries().get(0).id()
        );

        assertEquals(healthy.id, selection.account().id);
        assertEquals("http://healthy.test/live/user/pass/901.ts", selection.streamUrl());
    }

    @Test
    void loadsSeriesDetailsFromAnotherAccountWhenRequestedProviderFails() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        M3uPlaylistService playlists = mock(M3uPlaylistService.class);
        XtreamCatalogService xtream = mock(XtreamCatalogService.class);
        ProviderMetadataService metadata = mock(ProviderMetadataService.class);
        CatalogImageService images = mock(CatalogImageService.class);
        IptvCatalogService catalog = new IptvCatalogService(accounts, playlists, xtream, metadata, images);

        IptvAccount first = xtreamAccount(65L, "Source occupée", 1);
        IptvAccount second = xtreamAccount(66L, "Source libre", 0);
        M3uPlaylistService.Series firstSummary = seriesSummary(first, "1381");
        M3uPlaylistService.Series secondSummary = seriesSummary(second, "9042");
        M3uPlaylistService.Series secondDetails = seriesDetails(secondSummary, second, "7002");

        when(accounts.findByActiveTrueAndDisabledFalse()).thenReturn(List.of(first, second));
        when(accounts.findById(first.id)).thenReturn(Optional.of(first));
        when(xtream.load(second)).thenReturn(playlistWithSeries(secondSummary));
        when(xtream.loadSeries(second, secondSummary.id())).thenReturn(secondDetails);

        Map<String, Object> details = catalog.seriesInfo(firstSummary.id(), firstSummary.title());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> seasons = (List<Map<String, Object>>) details.get("seasons");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> episodes = (List<Map<String, Object>>) seasons.get(0).get("episodes");
        assertEquals("xtream-66-series-9042_7002", episodes.get(0).get("id"));
    }

    @Test
    void routesSeriesEpisodeFromSaturatedAccountToFreeEquivalentAccount() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        M3uPlaylistService playlists = mock(M3uPlaylistService.class);
        XtreamCatalogService xtream = mock(XtreamCatalogService.class);
        ProviderMetadataService metadata = mock(ProviderMetadataService.class);
        CatalogImageService images = mock(CatalogImageService.class);
        IptvCatalogService catalog = new IptvCatalogService(accounts, playlists, xtream, metadata, images);

        IptvAccount saturated = xtreamAccount(65L, "Source occupée", 1);
        IptvAccount free = xtreamAccount(66L, "Source libre", 0);
        M3uPlaylistService.Series saturatedSummary = seriesSummary(saturated, "1381");
        M3uPlaylistService.Series freeSummary = seriesSummary(free, "9042");
        M3uPlaylistService.Series saturatedDetails = seriesDetails(saturatedSummary, saturated, "5001");
        M3uPlaylistService.Series freeDetails = seriesDetails(freeSummary, free, "7002");

        when(accounts.findByActiveTrueAndDisabledFalse()).thenReturn(List.of(saturated, free));
        when(accounts.findById(saturated.id)).thenReturn(Optional.of(saturated));
        when(xtream.load(saturated)).thenReturn(playlistWithSeries(saturatedSummary));
        when(xtream.load(free)).thenReturn(playlistWithSeries(freeSummary));
        when(xtream.loadSeries(saturated, saturatedSummary.id())).thenReturn(saturatedDetails);
        when(xtream.loadSeries(free, freeSummary.id())).thenReturn(freeDetails);

        IptvCatalogService.StreamSelection selection = catalog.selectStream(
                "series",
                "xtream-65-series-1381_5001"
        );

        assertEquals(free.id, selection.account().id);
        assertEquals("http://66.test/series/user/pass/7002.mp4", selection.streamUrl());
    }

    @Test
    void routesSeriesAcrossProviderTitleYearAndRankingVariants() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        M3uPlaylistService playlists = mock(M3uPlaylistService.class);
        XtreamCatalogService xtream = mock(XtreamCatalogService.class);
        ProviderMetadataService metadata = mock(ProviderMetadataService.class);
        CatalogImageService images = mock(CatalogImageService.class);
        IptvCatalogService catalog = new IptvCatalogService(accounts, playlists, xtream, metadata, images);

        IptvAccount saturated = xtreamAccount(65L, "Source occupee", 1);
        IptvAccount free = xtreamAccount(33L, "Source libre", 0);
        M3uPlaylistService.Series saturatedSummary =
                seriesSummary(saturated, "5137", "#1 Happy Family USA (2025)");
        M3uPlaylistService.Series freeSummary =
                seriesSummary(free, "9042", "Happy Family USA");
        M3uPlaylistService.Series saturatedDetails =
                seriesDetails(saturatedSummary, saturated, "171669");
        M3uPlaylistService.Series freeDetails =
                seriesDetails(freeSummary, free, "7002");

        when(accounts.findByActiveTrueAndDisabledFalse()).thenReturn(List.of(saturated, free));
        when(accounts.findById(saturated.id)).thenReturn(Optional.of(saturated));
        when(xtream.load(saturated)).thenReturn(playlistWithSeries(saturatedSummary));
        when(xtream.load(free)).thenReturn(playlistWithSeries(freeSummary));
        when(xtream.loadSeries(saturated, saturatedSummary.id())).thenReturn(saturatedDetails);
        when(xtream.loadSeries(free, freeSummary.id())).thenReturn(freeDetails);

        IptvCatalogService.StreamSelection selection = catalog.selectStream(
                "series",
                "xtream-65-series-5137_171669"
        );

        assertEquals(free.id, selection.account().id);
        assertEquals("http://33.test/series/user/pass/7002.mp4", selection.streamUrl());
    }

    @Test
    void explainsWhenUniqueSeriesSourceIsOccupied() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        M3uPlaylistService playlists = mock(M3uPlaylistService.class);
        XtreamCatalogService xtream = mock(XtreamCatalogService.class);
        ProviderMetadataService metadata = mock(ProviderMetadataService.class);
        CatalogImageService images = mock(CatalogImageService.class);
        IptvCatalogService catalog = new IptvCatalogService(accounts, playlists, xtream, metadata, images);

        IptvAccount saturated = xtreamAccount(65L, "Source occupee", 1);
        IptvAccount free = xtreamAccount(33L, "Source libre", 0);
        M3uPlaylistService.Series uniqueSummary = seriesSummary(saturated, "4849", "1992");
        M3uPlaylistService.Series uniqueDetails = seriesDetails(uniqueSummary, saturated, "98913");

        when(accounts.findByActiveTrueAndDisabledFalse()).thenReturn(List.of(saturated, free));
        when(accounts.findById(saturated.id)).thenReturn(Optional.of(saturated));
        when(xtream.load(saturated)).thenReturn(playlistWithSeries(uniqueSummary));
        when(xtream.load(free)).thenReturn(playlistWithSeries(
                seriesSummary(free, "9000", "Another Series")
        ));
        when(xtream.loadSeries(saturated, uniqueSummary.id())).thenReturn(uniqueDetails);

        var exception = assertThrows(
                com.iptv.saas.web.ApiException.class,
                () -> catalog.selectStream("series", "xtream-65-series-4849_98913")
        );

        assertEquals(
                "Ce programme est disponible sur une seule source, actuellement occupee. "
                        + "Le compte IPTV libre ne possede pas ce contenu.",
                exception.getMessage()
        );
    }

    private IptvAccount account(Long id, String name) {
        IptvAccount account = new IptvAccount();
        account.id = id;
        account.name = name;
        account.accountType = Enums.IptvAccountType.M3U;
        account.active = true;
        account.maxStreams = 1;
        return account;
    }

    private IptvAccount xtreamAccount(Long id, String name, int activeStreams) {
        IptvAccount account = new IptvAccount();
        account.id = id;
        account.name = name;
        account.accountType = Enums.IptvAccountType.XTREAM;
        account.baseUrl = "http://" + id + ".test";
        account.username = "user";
        account.password = "pass";
        account.active = true;
        account.maxStreams = 1;
        account.activeStreams = activeStreams;
        return account;
    }

    private M3uPlaylistService.Series seriesSummary(IptvAccount account, String providerId) {
        return seriesSummary(account, providerId, "The Shared Series");
    }

    private M3uPlaylistService.Series seriesSummary(
            IptvAccount account,
            String providerId,
            String title
    ) {
        return new M3uPlaylistService.Series(
                "xtream-series-" + account.id + "-" + providerId,
                title,
                "series-category",
                "SERIES",
                "",
                List.of()
        );
    }

    private M3uPlaylistService.Series seriesDetails(
            M3uPlaylistService.Series summary,
            IptvAccount account,
            String streamId
    ) {
        String providerId = summary.id().substring(summary.id().lastIndexOf('-') + 1);
        M3uPlaylistService.Entry episode = new M3uPlaylistService.Entry(
                "xtream-" + account.id + "-series-" + providerId + "_" + streamId,
                "",
                summary.title() + " S01E01",
                "series",
                summary.categoryId(),
                summary.categoryName(),
                "",
                "http://" + account.id + ".test/series/user/pass/" + streamId + ".mp4",
                summary.id(),
                summary.title(),
                1,
                1,
                "Pilot"
        );
        return new M3uPlaylistService.Series(
                summary.id(),
                summary.title(),
                summary.categoryId(),
                summary.categoryName(),
                summary.poster(),
                List.of(episode)
        );
    }

    private M3uPlaylistService.Playlist playlistWithSeries(M3uPlaylistService.Series series) {
        return new M3uPlaylistService.Playlist(List.of(), List.of(), List.of(series));
    }

    private String playlist(String liveUrl, String movieUrl) {
        return """
                #EXTM3U
                #EXTINF:-1 tvg-id="news.one" group-title="NEWS",News One HD
                %s
                #EXTINF:-1 group-title="MOVIES",The Same Movie (2025)
                %s
                """.formatted(liveUrl, movieUrl);
    }
}
