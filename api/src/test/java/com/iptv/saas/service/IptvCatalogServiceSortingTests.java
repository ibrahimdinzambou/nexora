package com.iptv.saas.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IptvCatalogServiceSortingTests {
    @Test
    void ignoresLeadingArticlesWhenSortingTitles() {
        assertEquals("dernier passage", IptvCatalogService.sortableTitle("Le Dernier Passage"));
        assertEquals("matrix", IptvCatalogService.sortableTitle("The Matrix"));
        assertEquals("almost legends 2023", IptvCatalogService.sortableTitle("The (Almost) Legends (2023)"));
    }

    @Test
    void extractsTheLastReleaseYearFromMovieNames() {
        assertEquals(2024, M3uPlaylistService.releaseYear("Edition 1998 - Film remasterise (2024)"));
        assertEquals(0, M3uPlaylistService.releaseYear("Film sans annee"));
    }

    @Test
    void detectsLanguagesFromRealProviderCategoryFormats() {
        assertEquals("fr", IptvCatalogService.languageCode("FR | CINEMA VIP"));
        assertEquals("tr", IptvCatalogService.languageCode("TR ? SİNEVİZYON 2025"));
        assertEquals("en", IptvCatalogService.languageCode("AMAZON | Movies In English"));
        assertEquals("ar", IptvCatalogService.languageCode("ARAB | MBC & MYHD VIP"));
        assertEquals("multi", IptvCatalogService.languageCode("EU ? MULTI NETFLIX SERIES"));
    }
}
