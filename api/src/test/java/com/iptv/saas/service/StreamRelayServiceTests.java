package com.iptv.saas.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamRelayServiceTests {
    @Test
    void relaysRemoteVideoBytesAndContentType() throws Exception {
        byte[] payload = "mpeg-ts-demo".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stream.ts", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp2t");
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        try {
            StreamRelayService service = new StreamRelayService(4_194_304);
            var response = service.open(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/stream.ts",
                    null
            );
            try (var body = response.body()) {
                assertEquals(200, response.status());
                assertEquals("video/mp2t", response.contentType());
                assertEquals(payload.length, response.contentLength());
                assertArrayEquals(payload, body.readAllBytes());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void boundsOpenEndedBrowserRanges() throws Exception {
        AtomicReference<String> receivedRange = new AtomicReference<>();
        byte[] payload = "range-demo".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/movie.mkv", exchange -> {
            receivedRange.set(exchange.getRequestHeaders().getFirst("Range"));
            exchange.getResponseHeaders().set("Content-Type", "video/x-matroska");
            exchange.getResponseHeaders().set("Content-Range", "bytes 100-1123/5000000");
            exchange.sendResponseHeaders(206, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        try {
            StreamRelayService service = new StreamRelayService(1024);
            var response = service.open(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/movie.mkv",
                    "bytes=100-"
            );
            try (var body = response.body()) {
                body.readAllBytes();
            }

            assertEquals("bytes=100-262243", receivedRange.get());
            assertEquals(206, response.status());
            assertEquals(true, response.rangedRequest());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesOneTransientUpstreamFailure() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        byte[] payload = "recovered-stream".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/unstable.ts", exchange -> {
            if (attempts.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, -1);
            } else {
                exchange.getResponseHeaders().set("Content-Type", "video/mp2t");
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            }
            exchange.close();
        });
        server.start();

        try {
            StreamRelayService service = new StreamRelayService(1024);
            var response = service.open(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/unstable.ts",
                    null
            );
            try (var body = response.body()) {
                assertEquals(2, attempts.get());
                assertEquals(200, response.status());
                assertArrayEquals(payload, body.readAllBytes());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsAnEmptySuccessfulResponseAfterOneRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/empty.ts", exchange -> {
            attempts.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "video/mp2t");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        try {
            StreamRelayService service = new StreamRelayService(1024);

            assertThrows(
                    com.iptv.saas.web.ApiException.class,
                    () -> service.open(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/empty.ts",
                            null
                    )
            );
            assertEquals(2, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void assemblesBoundedRangesForRemuxing() throws Exception {
        List<String> receivedRanges = new CopyOnWriteArrayList<>();
        byte[] payload = new byte[300_000];
        Arrays.fill(payload, (byte) 7);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/episode.bin", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            receivedRanges.add(range);
            Matcher matcher = Pattern.compile("bytes=(\\d+)-(\\d+)").matcher(range);
            matcher.matches();
            int start = Integer.parseInt(matcher.group(1));
            int requestedEnd = Integer.parseInt(matcher.group(2));
            int end = Math.min(requestedEnd, payload.length - 1);
            byte[] chunk = Arrays.copyOfRange(payload, start, end + 1);
            exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + payload.length);
            exchange.sendResponseHeaders(206, chunk.length);
            exchange.getResponseBody().write(chunk);
            exchange.close();
        });
        server.start();

        try {
            StreamRelayService service = new StreamRelayService(1024);
            try (var body = service.openForRemux(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/episode.bin"
            )) {
                assertArrayEquals(payload, body.readAllBytes());
            }

            assertEquals(List.of("bytes=0-262143", "bytes=262144-524287"), receivedRanges);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesMatroskaMetadataForRemuxing() throws Exception {
        byte[] prefix = bytes(
                0x1A, 0x45, 0xDF, 0xA3, 0x80,
                0x18, 0x53, 0x80, 0x67, 0xFF,
                0x16, 0x54, 0xAE, 0x6B, 0x81, 0x00
        );
        byte[] attachment = new byte[1030];
        byte[] attachmentHeader = bytes(0x19, 0x41, 0xA4, 0x69, 0x44, 0x00);
        System.arraycopy(attachmentHeader, 0, attachment, 0, attachmentHeader.length);
        Arrays.fill(attachment, attachmentHeader.length, attachment.length, (byte) 9);
        byte[] tags = bytes(0x12, 0x54, 0xC3, 0x67, 0x82, 0x01, 0x02);
        byte[] cluster = new byte[270_007];
        byte[] clusterHeader = bytes(0x1F, 0x43, 0xB6, 0x75, 0x24, 0x1E, 0xB0);
        System.arraycopy(clusterHeader, 0, cluster, 0, clusterHeader.length);
        Arrays.fill(cluster, clusterHeader.length, cluster.length, (byte) 0x47);
        byte[] payload = join(prefix, attachment, tags, cluster);

        List<String> receivedRanges = new CopyOnWriteArrayList<>();
        HttpServer server = rangedServer(payload, receivedRanges, "/movie.mkv");
        server.start();
        try {
            StreamRelayService service = new StreamRelayService(1024);
            try (var body = service.openForRemux(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/movie.mkv"
            )) {
                assertArrayEquals(payload, body.readAllBytes());
            }

            assertEquals(List.of("bytes=0-262143", "bytes=262144-524287"), receivedRanges);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer rangedServer(byte[] payload, List<String> ranges, String path) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            ranges.add(range);
            Matcher matcher = Pattern.compile("bytes=(\\d+)-(\\d+)").matcher(range);
            matcher.matches();
            int start = Integer.parseInt(matcher.group(1));
            int requestedEnd = Integer.parseInt(matcher.group(2));
            int end = Math.min(requestedEnd, payload.length - 1);
            byte[] chunk = Arrays.copyOfRange(payload, start, end + 1);
            exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + payload.length);
            exchange.sendResponseHeaders(206, chunk.length);
            exchange.getResponseBody().write(chunk);
            exchange.close();
        });
        return server;
    }

    private byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    private byte[] join(byte[]... parts) {
        int length = Arrays.stream(parts).mapToInt(part -> part.length).sum();
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
