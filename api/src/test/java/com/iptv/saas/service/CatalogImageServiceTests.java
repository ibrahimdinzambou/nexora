package com.iptv.saas.service;

import com.iptv.saas.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogImageServiceTests {
    @Test
    void rewritesRemoteImagesInsideImmutableNestedPayloads() {
        CatalogImageService images = new CatalogImageService(1_048_576, 16);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("poster", "https://image.tmdb.org/t/p/w500/poster.jpg");
        payload.put("seasons", List.of(Map.of(
                "episodes",
                List.of(Map.of("image", "https://image.tmdb.org/t/p/w300/episode.jpg"))
        )));

        images.rewrite(payload);

        assertTrue(String.valueOf(payload.get("poster")).startsWith("/api/catalog/images/"));
        Map<?, ?> season = (Map<?, ?>) ((List<?>) payload.get("seasons")).get(0);
        Map<?, ?> episode = (Map<?, ?>) ((List<?>) season.get("episodes")).get(0);
        assertTrue(String.valueOf(episode.get("image")).startsWith("/api/catalog/images/"));
    }

    @Test
    void refusesPrivateImageTargetsWhenTheOpaqueUrlIsLoaded() {
        CatalogImageService images = new CatalogImageService(1_048_576, 16);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("poster", "http://127.0.0.1/private.jpg");
        images.rewrite(payload);
        String key = String.valueOf(payload.get("poster")).substring("/api/catalog/images/".length());

        ApiException exception = assertThrows(ApiException.class, () -> images.load(key));

        assertEquals(422, exception.status().value());
    }

    @Test
    void removesLocalFilesystemImagePathsFromCatalogPayloads() {
        CatalogImageService images = new CatalogImageService(1_048_576, 16);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("poster", "file:///S:/1:/images/poster.jpg");
        payload.put("backdrop", "S:\\1\\images\\backdrop.jpg");
        payload.put("logo", "/assets/images/landscape-1.jpg");

        images.rewrite(payload);

        assertEquals("", payload.get("poster"));
        assertEquals("", payload.get("backdrop"));
        assertEquals("/assets/images/landscape-1.jpg", payload.get("logo"));
    }
}
