package com.iptv.saas.web;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.domain.UserSession;
import com.iptv.saas.service.CommunityAddonService;
import com.iptv.saas.service.StreamRelayService;
import com.iptv.saas.service.StreamingService;
import com.iptv.saas.service.VlcRemuxService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StreamControllerTests {
    @Test
    void retriesTheProxyWithTheFailoverSessionAfterAnUpstreamFailure() {
        StreamingService streams = mock(StreamingService.class);
        StreamRelayService relay = mock(StreamRelayService.class);
        VlcRemuxService remux = mock(VlcRemuxService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        StreamController controller = controller(streams, relay, remux);

        UserSession failed = session("token", "https://failed.test/live.ts");
        UserSession replacement = session("token", "https://replacement.test/live.ts");
        when(streams.getActiveByToken("token")).thenReturn(failed);
        when(streams.failover(eq(failed), anySet())).thenReturn(replacement);
        when(request.getHeader(HttpHeaders.RANGE)).thenReturn(null);
        when(relay.open(failed.streamUrl, null))
                .thenThrow(ApiException.serviceUnavailable("upstream 503"));
        when(relay.open(replacement.streamUrl, null)).thenReturn(new StreamRelayService.RelayResponse(
                200,
                "video/mp2t",
                3L,
                null,
                null,
                false,
                new ByteArrayInputStream(new byte[]{1, 2, 3})
        ));

        ResponseEntity<StreamingResponseBody> response = controller.proxy("token", request);

        assertEquals(200, response.getStatusCode().value());
        verify(streams).failover(eq(failed), anySet());
        verify(relay).open(replacement.streamUrl, null);
    }

    @Test
    void triesSeveralFailoverSourcesBeforeReturningAHealthyProxy() {
        StreamingService streams = mock(StreamingService.class);
        StreamRelayService relay = mock(StreamRelayService.class);
        VlcRemuxService remux = mock(VlcRemuxService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        StreamController controller = controller(streams, relay, remux);

        UserSession first = session("token", "https://first.test/live.ts", 1L);
        UserSession second = session("token", "https://second.test/live.ts", 2L);
        UserSession third = session("token", "https://third.test/live.ts", 3L);
        when(streams.getActiveByToken("token")).thenReturn(first);
        when(streams.failover(eq(first), anySet())).thenReturn(second);
        when(streams.failover(eq(second), anySet())).thenReturn(third);
        when(request.getHeader(HttpHeaders.RANGE)).thenReturn(null);
        when(relay.open(first.streamUrl, null))
                .thenThrow(ApiException.serviceUnavailable("empty stream"));
        when(relay.open(second.streamUrl, null))
                .thenThrow(ApiException.serviceUnavailable("upstream 503"));
        when(relay.open(third.streamUrl, null)).thenReturn(new StreamRelayService.RelayResponse(
                200,
                "video/mp2t",
                3L,
                null,
                null,
                false,
                new ByteArrayInputStream(new byte[]{1, 2, 3})
        ));

        ResponseEntity<StreamingResponseBody> response = controller.proxy("token", request);

        assertEquals(200, response.getStatusCode().value());
        verify(streams).failover(eq(first), anySet());
        verify(streams).failover(eq(second), anySet());
        verify(relay).open(third.streamUrl, null);
    }

    @Test
    void preservesTheOriginalProxyFailureWhenFailoverIsUnavailable() {
        StreamingService streams = mock(StreamingService.class);
        StreamRelayService relay = mock(StreamRelayService.class);
        VlcRemuxService remux = mock(VlcRemuxService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        StreamController controller = controller(streams, relay, remux);

        UserSession failed = session("token", "https://failed.test/movie.mkv");
        ApiException original = ApiException.serviceUnavailable("Le flux MKV est vide ou incompatible");
        ApiException unavailable = ApiException.serviceUnavailable("Aucun autre compte ne peut servir ce contenu");
        when(streams.getActiveByToken("token")).thenReturn(failed);
        when(remux.requiresProcessing(failed.streamUrl, failed.playbackQuality)).thenReturn(true);
        when(relay.openForRemux(failed.streamUrl))
                .thenThrow(original);
        when(streams.failover(eq(failed), anySet())).thenThrow(unavailable);

        ApiException thrown = assertThrows(ApiException.class, () -> controller.proxy("token", request));

        assertSame(original, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(unavailable, thrown.getSuppressed()[0]);
    }

    @Test
    void exposesExplicitFailoverForAnActiveSession() {
        StreamingService streams = mock(StreamingService.class);
        StreamRelayService relay = mock(StreamRelayService.class);
        VlcRemuxService remux = mock(VlcRemuxService.class);
        StreamController controller = controller(streams, relay, remux);

        UserSession current = session("token", "https://failed.test/live.ts");
        UserSession replacement = session("token", "https://replacement.test/live.ts");
        when(streams.getActiveByToken("token")).thenReturn(current);
        when(streams.failover(current)).thenReturn(replacement);

        Map<String, Object> response = (Map<String, Object>) controller.failover("token");
        Map<String, Object> data = (Map<String, Object>) response.get("data");

        assertEquals(true, response.get("success"));
        assertEquals("token", data.get("token"));
        assertEquals("/api/stream/proxy/token", data.get("proxyUrl"));
        assertEquals("mpegts", data.get("playbackMode"));
        verify(streams).failover(current);
    }

    @Test
    void preflightSwitchesToAHealthySourceBeforePlaybackStarts() {
        StreamingService streams = mock(StreamingService.class);
        StreamRelayService relay = mock(StreamRelayService.class);
        VlcRemuxService remux = mock(VlcRemuxService.class);
        StreamController controller = controller(streams, relay, remux);

        UserSession failed = session("token", "https://failed.test/live.ts", 1L);
        UserSession replacement = session("token", "https://replacement.test/live.ts", 2L);
        when(streams.getActiveByToken("token")).thenReturn(failed);
        when(streams.failover(eq(failed), anySet())).thenReturn(replacement);
        doThrow(ApiException.serviceUnavailable("empty stream")).when(relay).probe(failed.streamUrl, false);

        Map<String, Object> response = (Map<String, Object>) controller.preflight("token");
        Map<String, Object> data = (Map<String, Object>) response.get("data");

        assertEquals(true, response.get("success"));
        assertEquals("/api/stream/proxy/token", data.get("proxyUrl"));
        verify(streams).failover(eq(failed), anySet());
        verify(relay).probe(replacement.streamUrl, false);
    }

    @Test
    void preflightDoesNotProbeNativeHlsPlaylistBeforePlaybackStarts() {
        StreamingService streams = mock(StreamingService.class);
        StreamRelayService relay = mock(StreamRelayService.class);
        VlcRemuxService remux = mock(VlcRemuxService.class);
        StreamController controller = controller(streams, relay, remux);

        UserSession session = session("token", "https://cdn.example/master/index.m3u8");
        session.contentType = "series";
        when(streams.getActiveByToken("token")).thenReturn(session);

        Map<String, Object> response = (Map<String, Object>) controller.preflight("token");
        Map<String, Object> data = (Map<String, Object>) response.get("data");

        assertEquals(true, response.get("success"));
        assertEquals("native", data.get("playbackMode"));
        assertEquals("/api/stream/proxy/token", data.get("proxyUrl"));
        verifyNoInteractions(relay);
    }

    @Test
    void rewritesRelativeHlsPlaylistEntriesThroughTheSessionProxy() throws Exception {
        StreamingService streams = mock(StreamingService.class);
        StreamRelayService relay = mock(StreamRelayService.class);
        VlcRemuxService remux = mock(VlcRemuxService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        StreamController controller = controller(streams, relay, remux);

        UserSession session = session("token", "https://cdn.example/master/index.m3u8");
        when(streams.getActiveByToken("token")).thenReturn(session);
        when(request.getHeader(HttpHeaders.RANGE)).thenReturn("bytes=0-");
        when(relay.open(session.streamUrl, null)).thenReturn(new StreamRelayService.RelayResponse(
                200,
                "application/vnd.apple.mpegurl",
                null,
                null,
                null,
                false,
                new ByteArrayInputStream("""
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=800000
                        child/k2demo/380/playlist.m3u8
                        """.getBytes(StandardCharsets.UTF_8))
        ));

        ResponseEntity<StreamingResponseBody> response = controller.proxy("token", request);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        String body = output.toString(StandardCharsets.UTF_8);
        String expectedTarget = "https://cdn.example/master/child/k2demo/380/playlist.m3u8";
        String encodedTarget = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(expectedTarget.getBytes(StandardCharsets.UTF_8));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(body.contains("/api/stream/hls/token?u=" + encodedTarget));
        verify(relay).open(session.streamUrl, null);
    }

    @Test
    void rewritesHlsPlaylistEntriesOnSiblingCdnSubdomains() throws Exception {
        StreamingService streams = mock(StreamingService.class);
        StreamRelayService relay = mock(StreamRelayService.class);
        VlcRemuxService remux = mock(VlcRemuxService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        StreamController controller = controller(streams, relay, remux);

        UserSession session = session("token", "https://hls15.cdnvideo11.shop/master/index.m3u8");
        when(streams.getActiveByToken("token")).thenReturn(session);
        when(request.getHeader(HttpHeaders.RANGE)).thenReturn(null);
        when(relay.open(session.streamUrl, null)).thenReturn(new StreamRelayService.RelayResponse(
                200,
                "application/vnd.apple.mpegurl",
                null,
                null,
                null,
                false,
                new ByteArrayInputStream("""
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=800000
                        https://hls16.cdnvideo11.shop/child/k2demo/380/playlist.m3u8
                        """.getBytes(StandardCharsets.UTF_8))
        ));

        ResponseEntity<StreamingResponseBody> response = controller.proxy("token", request);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        String body = output.toString(StandardCharsets.UTF_8);
        String expectedTarget = "https://hls16.cdnvideo11.shop/child/k2demo/380/playlist.m3u8";
        String encodedTarget = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(expectedTarget.getBytes(StandardCharsets.UTF_8));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(body.contains("/api/stream/hls/token?u=" + encodedTarget));
    }

    @Test
    void rewritesHlsPlaylistEntriesAllowedByAddonStreamHosts() throws Exception {
        StreamingService streams = mock(StreamingService.class);
        StreamRelayService relay = mock(StreamRelayService.class);
        VlcRemuxService remux = mock(VlcRemuxService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        StreamController controller = controller(streams, relay, remux, addons);

        UserSession session = session("token", "https://hls15.cdnvideo11.shop/master/index.m3u8");
        when(streams.getActiveByToken("token")).thenReturn(session);
        when(addons.isAllowedStreamHostForPlayback(eq(session.itemId), eq(session.user), eq("aapanel.devcorp.me")))
                .thenReturn(true);
        when(request.getHeader(HttpHeaders.RANGE)).thenReturn(null);
        when(relay.open(session.streamUrl, null)).thenReturn(new StreamRelayService.RelayResponse(
                200,
                "application/vnd.apple.mpegurl",
                null,
                null,
                null,
                false,
                new ByteArrayInputStream("""
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=800000
                        https://aapanel.devcorp.me/child/k2demo/380/playlist.m3u8
                        """.getBytes(StandardCharsets.UTF_8))
        ));

        ResponseEntity<StreamingResponseBody> response = controller.proxy("token", request);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        String body = output.toString(StandardCharsets.UTF_8);
        String expectedTarget = "https://aapanel.devcorp.me/child/k2demo/380/playlist.m3u8";
        String encodedTarget = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(expectedTarget.getBytes(StandardCharsets.UTF_8));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(body.contains("/api/stream/hls/token?u=" + encodedTarget));
    }

    @Test
    void rejectsHlsPlaylistEntriesOnUnrelatedDomains() {
        StreamingService streams = mock(StreamingService.class);
        StreamRelayService relay = mock(StreamRelayService.class);
        VlcRemuxService remux = mock(VlcRemuxService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        StreamController controller = controller(streams, relay, remux);

        UserSession session = session("token", "https://hls15.cdnvideo11.shop/master/index.m3u8");
        when(streams.getActiveByToken("token")).thenReturn(session);
        String encodedTarget = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("https://unrelated.example/playlist.m3u8".getBytes(StandardCharsets.UTF_8));

        ApiException exception = assertThrows(
                ApiException.class,
                () -> controller.hls("token", encodedTarget, request)
        );

        assertEquals(403, exception.status().value());
    }

    private StreamController controller(StreamingService streams, StreamRelayService relay, VlcRemuxService remux) {
        return controller(streams, relay, remux, mock(CommunityAddonService.class));
    }

    private StreamController controller(
            StreamingService streams,
            StreamRelayService relay,
            VlcRemuxService remux,
            CommunityAddonService addons
    ) {
        return new StreamController(streams, relay, remux, addons);
    }

    private UserSession session(String token, String streamUrl) {
        return session(token, streamUrl, 1L);
    }

    private UserSession session(String token, String streamUrl, Long accountId) {
        UserSession session = new UserSession();
        session.sessionToken = token;
        session.contentType = "live";
        session.itemId = "shared-channel";
        session.streamUrl = streamUrl;
        session.playbackQuality = "auto";
        session.iptvAccount = new IptvAccount();
        session.iptvAccount.id = accountId;
        session.status = Enums.SessionStatus.ACTIVE;
        return session;
    }
}
