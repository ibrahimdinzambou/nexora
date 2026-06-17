package com.iptv.saas.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VlcRemuxServiceTests {
    @Test
    void identifiesMatroskaStreamsWithoutRemuxingBrowserNativeFormats() {
        VlcRemuxService service = new VlcRemuxService("", 75);

        assertTrue(service.requiresRemux("https://provider.test/movie/123.mkv?token=abc"));
        assertFalse(service.requiresRemux("https://provider.test/movie/123.mp4"));
        assertFalse(service.requiresRemux("https://provider.test/live/123.ts"));
    }

    @Test
    void mapsManualQualityChoicesToBoundedTranscodeProfiles() {
        VlcRemuxService service = new VlcRemuxService("", 75);

        assertEquals("auto", VlcRemuxService.normalizeQuality("invalid"));
        assertEquals(480, service.qualityProfile("data").height());
        assertEquals(1_200, service.qualityProfile("data").videoBitrateKbps());
        assertEquals(720, service.qualityProfile("hd").height());
        assertEquals(2_500, service.qualityProfile("hd").videoBitrateKbps());
        assertEquals(1080, service.qualityProfile("fullhd").height());
        assertEquals(5_000, service.qualityProfile("fullhd").videoBitrateKbps());
        assertNull(service.qualityProfile("auto"));
        assertNull(service.qualityProfile("uhd"));
        assertTrue(service.requiresTranscode("hd"));
        assertFalse(service.requiresTranscode("uhd"));
    }
}
