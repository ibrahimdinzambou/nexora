package com.iptv.saas.web;

import com.iptv.saas.service.CatalogImageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogImageControllerTests {
    @Test
    void returnsPlaceholderWhenRemoteImageCannotBeLoaded() {
        CatalogImageService images = mock(CatalogImageService.class);
        when(images.load("missing")).thenThrow(ApiException.notFound("Image introuvable"));

        ResponseEntity<byte[]> response = new CatalogImageController(images).image("missing");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("image/svg+xml", response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertTrue(new String(response.getBody()).contains("<svg"));
    }
}
