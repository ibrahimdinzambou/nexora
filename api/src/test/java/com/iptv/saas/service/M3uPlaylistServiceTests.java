package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.IptvAccount;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M3uPlaylistServiceTests {
    private final M3uPlaylistService service = new M3uPlaylistService(
            900,
            2_000_000,
            "target/test-m3u-cache",
            168,
            30
    );

    @Test
    void parsesLiveMoviesAndSeriesWithoutExposingStreamUrls() {
        IptvAccount account = new IptvAccount();
        account.id = 42L;
        account.accountType = Enums.IptvAccountType.M3U;

        String playlist = """
                #EXTM3U
                #EXTINF:-1 tvg-name="News One" tvg-logo="https://img.test/news.png" group-title="FR | NEWS",News One
                http://provider.test/live/secret/1001.ts
                #EXTINF:-1 tvg-name="Cinema One" tvg-logo="https://img.test/movie.jpg" group-title="MOVIES | ACTION",Cinema One (2024)
                http://provider.test/movie/secret/2001.mp4
                #EXTINF:-1 tvg-name="Episode One" group-title="SERIES | DRAMA",Episode One
                http://provider.test/series/secret/3001.mp4
                """;

        M3uPlaylistService.Playlist parsed = service.parse(account, playlist);

        assertEquals(3, parsed.entries().size());
        assertEquals(3, parsed.categories().size());
        assertEquals("live", parsed.entries().get(0).type());
        assertEquals("movie", parsed.entries().get(1).type());
        assertEquals(2024, parsed.entries().get(1).apiPayload().get("releaseYear"));
        assertEquals("series", parsed.entries().get(2).type());
        assertTrue(parsed.entries().get(0).id().startsWith("m3u-42-"));
        assertEquals("http://provider.test/live/secret/1001.ts",
                parsed.find(parsed.entries().get(0).id()).streamUrl());
        assertFalse(parsed.entries().get(0).apiPayload().toString().contains("provider.test"));
        assertFalse(parsed.entries().get(0).apiPayload().toString().contains("secret"));
    }

    @Test
    void groupsSeriesEpisodesByTitleAndSeason() {
        IptvAccount account = new IptvAccount();
        account.id = 42L;
        account.accountType = Enums.IptvAccountType.M3U;

        String playlist = """
                #EXTM3U
                #EXTINF:-1 tvg-logo="https://img.test/show.jpg" group-title="AMAZON | 2025",#1 Happy Family USA S01 E01
                http://provider.test/series/secret/3001.mp4
                #EXTINF:-1 tvg-logo="https://img.test/show.jpg" group-title="AMAZON | 2025",#1 Happy Family USA S01 E02 - Le départ
                http://provider.test/series/secret/3002.mp4
                #EXTINF:-1 tvg-logo="https://img.test/show.jpg" group-title="AMAZON | 2025",Happy Family USA S02E01
                http://provider.test/series/secret/3003.mp4
                #EXTINF:-1 group-title="SERIES | DRAMA",Another Show 2x03 - Retour
                http://provider.test/series/secret/4003.mp4
                """;

        M3uPlaylistService.Playlist parsed = service.parse(account, playlist);

        assertEquals(2, parsed.series().size());
        M3uPlaylistService.Series happyFamily = parsed.series().stream()
                .filter(series -> series.title().equals("Happy Family USA"))
                .findFirst()
                .orElseThrow();

        assertEquals(3, happyFamily.episodes().size());
        assertEquals(2L, happyFamily.apiPayload().get("seasonCount"));
        assertEquals(3, happyFamily.apiPayload().get("episodeCount"));
        assertEquals("Le départ", happyFamily.episodes().get(1).episodeTitle());
        assertEquals(2, happyFamily.episodes().get(2).seasonNumber());
        assertTrue(happyFamily.id().startsWith("m3u-series-42-"));
        assertFalse(happyFamily.detailPayload().toString().contains("provider.test"));
        assertFalse(happyFamily.detailPayload().toString().contains("secret"));
    }

    @Test
    void separatesMisroutedEpisodesFromRealMovies() {
        IptvAccount account = new IptvAccount();
        account.id = 42L;
        account.accountType = Enums.IptvAccountType.M3U;

        String playlist = """
                #EXTM3U
                #EXTINF:-1 group-title="MOVIES | ACTION",Night Watch S01E02 - La fuite
                http://provider.test/movie/secret/5002.mkv
                #EXTINF:-1 group-title="MOVIES | ACTION",Another Story 1x03 - Retour
                http://provider.test/movie/secret/5003.mp4
                #EXTINF:-1 group-title="MOVIES | THRILLER",10x10 (2018)
                http://provider.test/movie/secret/5010.mkv
                #EXTINF:-1 group-title="MOVIES | DOCUMENTARY",The Making of Loki Season 2 (2023)
                http://provider.test/movie/secret/5011.mkv
                """;

        M3uPlaylistService.Playlist parsed = service.parse(account, playlist);

        assertEquals("series", parsed.entries().get(0).type());
        assertEquals("Night Watch", parsed.entries().get(0).seriesTitle());
        assertEquals(1, parsed.entries().get(0).seasonNumber());
        assertEquals(2, parsed.entries().get(0).episodeNumber());
        assertEquals("series", parsed.entries().get(1).type());
        assertEquals("movie", parsed.entries().get(2).type());
        assertEquals("movie", parsed.entries().get(3).type());
        assertEquals(2, parsed.series().size());
    }
}
