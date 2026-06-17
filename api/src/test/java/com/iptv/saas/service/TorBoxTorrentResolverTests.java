package com.iptv.saas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.web.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorBoxTorrentResolverTests {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void resolvesAnInfoHashToTheLargestVideoFile() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> createBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/api/torrents/createtorrent", exchange -> {
            createBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, """
                    {"success":true,"data":{"torrent_id":42}}
                    """);
        });
        server.createContext("/v1/api/torrents/mylist", exchange -> respond(exchange, """
                {
                  "success": true,
                  "data": {
                    "id": 42,
                    "download_state": "completed",
                    "files": [
                      {"id": 1, "name": "sample.mp4", "size": 1000},
                      {"id": 2, "name": "feature.mkv", "size": 9000},
                      {"id": 3, "name": "readme.txt", "size": 50000}
                    ]
                  }
                }
                """));
        server.createContext("/v1/api/torrents/requestdl", exchange -> {
            assertTrue(exchange.getRequestURI().getQuery().contains("token=test-token"));
            assertTrue(exchange.getRequestURI().getQuery().contains("torrent_id=42"));
            assertTrue(exchange.getRequestURI().getQuery().contains("file_id=2"));
            respond(exchange, """
                    {"success":true,"data":"https://download.torbox.app/feature.mkv"}
                    """);
        });
        server.start();

        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        TorBoxTorrentResolver resolver = new TorBoxTorrentResolver(
                new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                root,
                "test-token",
                ".torbox.app",
                2,
                50
        );
        JsonNode stream = new ObjectMapper().readTree("""
                {
                  "infoHash": "0123456789abcdef0123456789abcdef01234567",
                  "sources": ["tracker:https://tracker.example/announce"]
                }
                """);

        String url = resolver.resolve(stream);

        assertEquals("https://download.torbox.app/feature.mkv", url);
        assertEquals("Bearer test-token", authorization.get());
        assertTrue(createBody.get().contains(
                "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
        ));
        assertTrue(createBody.get().contains("tr=https%3A%2F%2Ftracker.example%2Fannounce"));
    }

    @Test
    void rejectsADownloadUrlOutsideTheTorBoxAllowlist() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/api/torrents/createtorrent", exchange ->
                respond(exchange, "{\"success\":true,\"data\":{\"torrent_id\":42}}"));
        server.createContext("/v1/api/torrents/mylist", exchange -> respond(exchange, """
                {"success":true,"data":{"download_state":"completed","files":[
                  {"id":1,"name":"feature.mp4","size":9000}
                ]}}
                """));
        server.createContext("/v1/api/torrents/requestdl", exchange ->
                respond(exchange, "{\"success\":true,\"data\":\"https://untrusted.example/feature.mp4\"}"));
        server.start();

        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        TorBoxTorrentResolver resolver = new TorBoxTorrentResolver(
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                root,
                "test-token",
                ".torbox.app",
                1,
                50
        );
        JsonNode stream = new ObjectMapper().readTree("""
                {"infoHash":"0123456789abcdef0123456789abcdef01234567"}
                """);

        assertThrows(ApiException.class, () -> resolver.resolve(stream));
    }

    @Test
    void acceptsTheOfficialTorBoxCdnDomain() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/api/torrents/createtorrent", exchange ->
                respond(exchange, "{\"success\":true,\"data\":{\"torrent_id\":42}}"));
        server.createContext("/v1/api/torrents/mylist", exchange -> respond(exchange, """
                {"success":true,"data":{"download_state":"cached","files":[
                  {"id":1,"name":"feature.mp4","size":9000}
                ]}}
                """));
        server.createContext("/v1/api/torrents/requestdl", exchange ->
                respond(exchange, "{\"success\":true,\"data\":\"https://store-078.wnam.tb-cdn.io/file\"}"));
        server.start();

        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        TorBoxTorrentResolver resolver = new TorBoxTorrentResolver(
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                root,
                "test-token",
                ".torbox.app,.tb-cdn.io",
                1,
                50
        );
        JsonNode stream = new ObjectMapper().readTree("""
                {"infoHash":"0123456789abcdef0123456789abcdef01234567"}
                """);

        assertEquals("https://store-078.wnam.tb-cdn.io/file", resolver.resolve(stream));
    }

    @Test
    void treatsTorBoxPlanRestrictionsAsPaymentRequired() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/api/torrents/createtorrent", exchange -> respond(exchange, """
                {"success":false,"detail":"API feature not available on your plan. Please upgrade to a paid plan to access the API."}
                """));
        server.start();

        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        TorBoxTorrentResolver resolver = new TorBoxTorrentResolver(
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                root,
                "test-token",
                ".torbox.app",
                1,
                50
        );
        JsonNode stream = new ObjectMapper().readTree("""
                {"infoHash":"0123456789abcdef0123456789abcdef01234567"}
                """);

        ApiException exception = assertThrows(ApiException.class, () -> resolver.resolve(stream));

        assertEquals(402, exception.status().value());
        assertEquals("payment_required", exception.code());
    }

    @Test
    void reusesTheCreatedTorrentAfterAnInitialTimeout() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger createRequests = new AtomicInteger();
        AtomicBoolean ready = new AtomicBoolean();
        server.createContext("/v1/api/torrents/createtorrent", exchange -> {
            createRequests.incrementAndGet();
            respond(exchange, "{\"success\":true,\"data\":{\"torrent_id\":42}}");
        });
        server.createContext("/v1/api/torrents/mylist", exchange -> respond(exchange, ready.get()
                ? """
                  {"success":true,"data":{"download_state":"completed","files":[
                    {"id":1,"name":"feature.mp4","size":9000}
                  ]}}
                  """
                : """
                  {"success":true,"data":{"download_state":"downloading","files":[
                    {"id":1,"name":"feature.mp4","size":9000}
                  ]}}
                  """));
        server.createContext("/v1/api/torrents/requestdl", exchange ->
                respond(exchange, "{\"success\":true,\"data\":\"https://download.torbox.app/feature.mp4\"}"));
        server.start();

        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        TorBoxTorrentResolver resolver = new TorBoxTorrentResolver(
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                root,
                "test-token",
                ".torbox.app",
                1,
                50
        );
        JsonNode stream = new ObjectMapper().readTree("""
                {"infoHash":"0123456789abcdef0123456789abcdef01234567"}
                """);

        assertThrows(ApiException.class, () -> resolver.resolve(stream));
        ready.set(true);

        assertEquals("https://download.torbox.app/feature.mp4", resolver.resolve(stream));
        assertEquals(1, createRequests.get());
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
