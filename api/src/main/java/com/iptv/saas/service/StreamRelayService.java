package com.iptv.saas.service;

import com.iptv.saas.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class StreamRelayService {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamRelayService.class);
    private static final int MATROSKA_PROBE_BYTES = 4096;
    private static final int MATROSKA_PREFETCH_CHUNKS = 4;
    private static final int MATROSKA_PREFETCH_CHUNK_BYTES = 65_536;
    private static final long EBML_ID = 0x1A45DFA3L;
    private static final long SEGMENT_ID = 0x18538067L;
    private static final long CLUSTER_ID = 0x1F43B675L;
    private static final long SEEK_HEAD_ID = 0x114D9B74L;
    private static final long VOID_ID = 0xECL;
    private static final Pattern OPEN_RANGE = Pattern.compile("^bytes=(\\d+)-$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_RANGE = Pattern.compile(
            "^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$",
            Pattern.CASE_INSENSITIVE
    );

    private final HttpClient httpClient;
    private final long rangeChunkBytes;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration probeTimeout;
    private final ExecutorService metadataExecutor = Executors.newFixedThreadPool(
            MATROSKA_PREFETCH_CHUNKS,
            runnable -> {
                Thread thread = new Thread(runnable, "matroska-prefetch");
                thread.setDaemon(true);
                return thread;
            }
    );

    @Autowired
    public StreamRelayService(
            @Value("${app.iptv.proxy-range-chunk-bytes:2097152}") long rangeChunkBytes,
            @Value("${app.iptv.proxy-connect-timeout-seconds:8}") int connectTimeoutSeconds,
            @Value("${app.iptv.proxy-request-timeout-seconds:12}") int requestTimeoutSeconds,
            @Value("${app.iptv.proxy-probe-timeout-seconds:4}") int probeTimeoutSeconds
    ) {
        this.connectTimeout = Duration.ofSeconds(Math.max(2, connectTimeoutSeconds));
        this.requestTimeout = Duration.ofSeconds(Math.max(3, requestTimeoutSeconds));
        this.probeTimeout = Duration.ofSeconds(Math.max(2, probeTimeoutSeconds));
        this.httpClient = newRelayClient();
        this.rangeChunkBytes = Math.max(262_144, rangeChunkBytes);
    }

    public StreamRelayService(long rangeChunkBytes) {
        this(rangeChunkBytes, 8, 12, 4);
    }

    public RelayResponse open(String streamUrl, String range) {
        String normalizedRange = normalizeRange(range);
        for (int attempt = 0; attempt < 2; attempt++) {
            RelayResponse response = openWithRange(streamUrl, normalizedRange);
            try {
                return requireNonEmptyBody(response);
            } catch (IOException exception) {
                closeQuietly(response.body());
                if (attempt == 0) {
                    pauseBeforeRetry();
                    continue;
                }
                throw ApiException.serviceUnavailable("Le fournisseur a renvoye un flux video vide");
            }
        }
        throw ApiException.serviceUnavailable("Le fournisseur a renvoye un flux video vide");
    }

    public InputStream openForRemux(String streamUrl) {
        // VLC needs the original EBML layout. Removing metadata can invalidate
        // segment offsets and make otherwise healthy MKV streams exit early.
        return openFrom(streamUrl, 0);
    }

    public void probe(String streamUrl, boolean hlsPlaylist) {
        String range = hlsPlaylist ? null : "bytes=0-0";
        RelayResponse response = openWithRange(newRelayClient(), streamUrl, range, probeTimeout);
        try (InputStream body = response.body()) {
            if (response.contentLength() != null && response.contentLength() == 0) {
                throw ApiException.serviceUnavailable("Le fournisseur a renvoye un flux video vide");
            }
            if (body.read() < 0) {
                throw ApiException.serviceUnavailable("Le fournisseur a renvoye un flux video vide");
            }
        } catch (IOException exception) {
            throw ApiException.serviceUnavailable("Le fournisseur a interrompu la verification du flux");
        }
    }

    private InputStream openMatroskaWithoutHeavyMetadata(String streamUrl) {
        long startedAt = System.nanoTime();
        try {
            byte[] initial = readProbe(streamUrl, 0);
            MatroskaPrefix prefix = matroskaPrefix(initial);
            if (prefix == null) {
                return null;
            }

            byte[] prefetched = prefetchMatroska(streamUrl, prefix.nextOffset());
            int clusterOffset = firstClusterOffset(prefetched);
            if (clusterOffset < 0) {
                LOGGER.warn("Optimisation Matroska abandonnee: aucun cluster dans le prechargement");
                return null;
            }
            long tailOffset = Math.addExact(prefix.nextOffset(), prefetched.length);
            InputStream media = new SequenceInputStream(
                    new ByteArrayInputStream(
                            prefetched,
                            clusterOffset,
                            prefetched.length - clusterOffset
                    ),
                    openFrom(streamUrl, tailOffset)
            );
            InputStream optimized = new SequenceInputStream(
                    new ByteArrayInputStream(prefix.bytes()),
                    media
            );
            LOGGER.info(
                    "Optimisation Matroska prete en {} ms, {} octets precharges",
                    elapsedMillis(startedAt),
                    prefetched.length
            );
            return optimized;
        } catch (IOException | ArithmeticException exception) {
            LOGGER.warn(
                    "Optimisation Matroska indisponible apres {} ms: {}",
                    elapsedMillis(startedAt),
                    rootMessage(exception)
            );
            return null;
        }
    }

    private byte[] prefetchMatroska(String streamUrl, long offset) throws IOException {
        List<Future<byte[]>> futures = new ArrayList<>(MATROSKA_PREFETCH_CHUNKS);
        for (int index = 0; index < MATROSKA_PREFETCH_CHUNKS; index++) {
            long start = offset + (long) index * MATROSKA_PREFETCH_CHUNK_BYTES;
            futures.add(metadataExecutor.submit(() ->
                    readRange(streamUrl, start, MATROSKA_PREFETCH_CHUNK_BYTES)));
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(
                MATROSKA_PREFETCH_CHUNKS * MATROSKA_PREFETCH_CHUNK_BYTES
        );
        try {
            for (int index = 0; index < futures.size(); index++) {
                long chunkStartedAt = System.nanoTime();
                byte[] chunk = futures.get(index).get(75, TimeUnit.SECONDS);
                LOGGER.info(
                        "Prechargement Matroska segment {} termine en {} ms ({} octets)",
                        index,
                        elapsedMillis(chunkStartedAt),
                        chunk.length
                );
                output.writeBytes(chunk);
            }
            return output.toByteArray();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Prechargement Matroska interrompu", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IOException("Prechargement Matroska impossible", exception);
        } finally {
            futures.forEach(future -> future.cancel(true));
        }
    }

    private byte[] readRange(String streamUrl, long offset, int length) throws IOException {
        long end = offset + length - 1L;
        RelayResponse response = openWithRange(
                newRelayClient(),
                streamUrl,
                "bytes=" + offset + "-" + end
        );
        if (offset > 0 && response.status() != 206) {
            response.body().close();
            throw new IOException("Le fournisseur ne respecte pas les plages video");
        }
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(length);
            if (bytes.length == 0) {
                throw new IOException("Segment Matroska vide");
            }
            return bytes;
        }
    }

    private int firstClusterOffset(byte[] bytes) {
        long offset = 0;
        while (offset < bytes.length) {
            EbmlElement element = ebmlElement(bytes, (int) offset);
            if (element == null || element.unknownSize()) {
                return -1;
            }
            if (element.id() == CLUSTER_ID) {
                return (int) offset;
            }
            offset += element.totalLength();
        }
        return -1;
    }

    private byte[] readProbe(String streamUrl, long offset) throws IOException {
        long end = offset + MATROSKA_PROBE_BYTES - 1L;
        RelayResponse response = openWithRange(streamUrl, "bytes=" + offset + "-" + end);
        try (InputStream body = response.body()) {
            return body.readNBytes(MATROSKA_PROBE_BYTES);
        }
    }

    private InputStream openFrom(String streamUrl, long offset) {
        RelayResponse firstChunk = open(streamUrl, "bytes=" + offset + "-");
        return new ChunkedRelayInputStream(streamUrl, firstChunk, offset);
    }

    private MatroskaPrefix matroskaPrefix(byte[] probe) {
        EbmlElement ebml = ebmlElement(probe, 0);
        if (ebml == null || ebml.id() != EBML_ID || ebml.unknownSize()) {
            return null;
        }
        long segmentOffset = ebml.totalLength();
        if (segmentOffset > Integer.MAX_VALUE) {
            return null;
        }
        EbmlElement segment = ebmlElement(probe, (int) segmentOffset);
        if (segment == null || segment.id() != SEGMENT_ID) {
            return null;
        }

        long offset = segmentOffset + segment.headerLength();
        ByteArrayOutputStream cleaned = new ByteArrayOutputStream(probe.length);
        cleaned.write(probe, 0, (int) offset);
        while (offset < probe.length) {
            EbmlElement child = ebmlElement(probe, (int) offset);
            if (child == null || child.unknownSize()) {
                return null;
            }
            if (isHeavyMatroskaMetadata(child.id())) {
                long nextOffset = offset + child.totalLength();
                return new MatroskaPrefix(cleaned.toByteArray(), nextOffset);
            }
            if (offset + child.totalLength() > probe.length) {
                return null;
            }
            if (child.id() != SEEK_HEAD_ID && child.id() != VOID_ID) {
                cleaned.write(probe, (int) offset, (int) child.totalLength());
            }
            offset += child.totalLength();
        }
        return null;
    }

    private EbmlElement ebmlElement(byte[] bytes, int offset) {
        EbmlVint id = ebmlVint(bytes, offset, false);
        if (id == null) {
            return null;
        }
        EbmlVint size = ebmlVint(bytes, offset + id.length(), true);
        if (size == null) {
            return null;
        }
        long headerLength = id.length() + size.length();
        long totalLength = headerLength + size.value();
        return new EbmlElement(id.value(), headerLength, totalLength, size.unknown());
    }

    private EbmlVint ebmlVint(byte[] bytes, int offset, boolean size) {
        if (offset < 0 || offset >= bytes.length) {
            return null;
        }
        int first = Byte.toUnsignedInt(bytes[offset]);
        int marker = 0x80;
        int length = 1;
        while (length <= 8 && (first & marker) == 0) {
            marker >>= 1;
            length++;
        }
        if (length > 8 || offset + length > bytes.length) {
            return null;
        }

        long value = size ? first & (marker - 1) : first;
        for (int index = 1; index < length; index++) {
            value = (value << 8) | Byte.toUnsignedInt(bytes[offset + index]);
        }
        long unknownValue = length == 8 ? Long.MAX_VALUE : (1L << (length * 7)) - 1;
        return new EbmlVint(value, length, size && value == unknownValue);
    }

    private boolean isHeavyMatroskaMetadata(long id) {
        return id == 0x1941A469L
                || id == 0x1254C367L
                || id == 0x1C53BB6BL
                || id == 0x1043A770L;
    }

    private boolean isMatroska(String streamUrl) {
        try {
            return URI.create(streamUrl).getPath().toLowerCase(Locale.ROOT).endsWith(".mkv");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        metadataExecutor.shutdownNow();
    }

    private RelayResponse openWithRange(String streamUrl, String upstreamRange) {
        return openWithRange(httpClient, streamUrl, upstreamRange);
    }

    private RelayResponse openWithRange(HttpClient client, String streamUrl, String upstreamRange) {
        return openWithRange(client, streamUrl, upstreamRange, requestTimeout);
    }

    private RelayResponse openWithRange(HttpClient client, String streamUrl, String upstreamRange, Duration timeout) {
        URI uri = streamUri(streamUrl);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(timeout)
                .header("Accept", "*/*")
                .header("User-Agent", "VLC/3.0 Nexora-IPTV");
        if (upstreamRange != null) {
            request.header("Range", upstreamRange);
        }

        HttpRequest httpRequest = request.GET().build();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<InputStream> response = client.send(
                        httpRequest,
                        HttpResponse.BodyHandlers.ofInputStream()
                );
                if (response.statusCode() != 200 && response.statusCode() != 206) {
                    int status = response.statusCode();
                    response.body().close();
                    if (status >= 500 && attempt == 0) {
                        pauseBeforeRetry();
                        continue;
                    }
                    throw ApiException.serviceUnavailable("Le fournisseur a refusé le flux vidéo");
                }

                String contentType = response.headers().firstValue("Content-Type")
                        .orElseGet(() -> inferredContentType(uri.getPath()));
                OptionalLong contentLength = response.headers().firstValueAsLong("Content-Length");
                Optional<String> contentRange = response.headers().firstValue("Content-Range");
                Optional<String> acceptRanges = response.headers().firstValue("Accept-Ranges");
                return new RelayResponse(
                        response.statusCode(),
                        contentType,
                        contentLength.isPresent() ? contentLength.getAsLong() : null,
                        contentRange.orElse(null),
                        acceptRanges.orElse(null),
                        upstreamRange != null,
                        response.body()
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw ApiException.serviceUnavailable("Connexion au flux interrompue");
            } catch (IOException exception) {
                if (attempt == 0) {
                    pauseBeforeRetry();
                    continue;
                }
                throw ApiException.serviceUnavailable("Impossible de joindre le flux vidéo");
            }
        }
        throw ApiException.serviceUnavailable("Impossible de joindre le flux vidéo");
    }

    private HttpClient newRelayClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private RelayResponse requireNonEmptyBody(RelayResponse response) throws IOException {
        if (response.contentLength() != null && response.contentLength() == 0) {
            throw new IOException("Corps video vide");
        }
        int firstByte = response.body().read();
        if (firstByte < 0) {
            throw new IOException("Corps video vide");
        }
        InputStream body = new SequenceInputStream(
                new ByteArrayInputStream(new byte[]{(byte) firstByte}),
                response.body()
        );
        return new RelayResponse(
                response.status(),
                response.contentType(),
                response.contentLength(),
                response.contentRange(),
                response.acceptRanges(),
                response.rangedRequest(),
                body
        );
    }

    private void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException exception) {
            // The upstream body is already unusable.
        }
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private String rootMessage(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }

    private void pauseBeforeRetry() {
        try {
            Thread.sleep(120);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Connexion au flux interrompue");
        }
    }

    String normalizeRange(String range) {
        if (range == null || range.isBlank()) {
            return null;
        }
        Matcher matcher = OPEN_RANGE.matcher(range.strip());
        if (!matcher.matches()) {
            return range.strip();
        }

        long start;
        try {
            start = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            return range.strip();
        }
        long end = start > Long.MAX_VALUE - rangeChunkBytes
                ? Long.MAX_VALUE
                : start + rangeChunkBytes - 1;
        return "bytes=" + start + "-" + end;
    }

    private URI streamUri(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw ApiException.validation("URL de flux invalide");
        }
    }

    private String inferredContentType(String path) {
        String normalized = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (normalized.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }
        return "video/mp2t";
    }

    private final class ChunkedRelayInputStream extends InputStream {
        private final String streamUrl;
        private RelayResponse response;
        private InputStream current;
        private long position;
        private long chunkStart;
        private Long totalLength;
        private boolean finished;
        private int retries;

        private ChunkedRelayInputStream(String streamUrl, RelayResponse firstChunk, long position) {
            this.streamUrl = streamUrl;
            this.position = position;
            use(firstChunk);
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read < 0 ? -1 : single[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (finished) {
                return -1;
            }
            while (true) {
                try {
                    int read = current.read(buffer, offset, length);
                    if (read > 0) {
                        position += read;
                        retries = 0;
                        return read;
                    }
                    if (read == 0) {
                        int single = current.read();
                        if (single >= 0) {
                            buffer[offset] = (byte) single;
                            position++;
                            retries = 0;
                            return 1;
                        }
                    }
                } catch (IOException exception) {
                    if (++retries > 2) {
                        throw exception;
                    }
                    openNextChunk();
                    continue;
                }

                if (response.status() == 200 || (totalLength != null && position >= totalLength)) {
                    finished = true;
                    closeCurrent();
                    return -1;
                }
                if (position <= chunkStart && ++retries > 2) {
                    throw new IOException("Le fournisseur a renvoye un segment video vide");
                }
                openNextChunk();
            }
        }

        @Override
        public void close() throws IOException {
            finished = true;
            closeCurrent();
        }

        private void openNextChunk() throws IOException {
            closeCurrent();
            try {
                use(StreamRelayService.this.open(streamUrl, "bytes=" + position + "-"));
            } catch (ApiException exception) {
                throw new IOException(exception.getMessage(), exception);
            }
        }

        private void use(RelayResponse next) {
            this.response = next;
            this.current = next.body();
            Matcher matcher = CONTENT_RANGE.matcher(next.contentRange() == null ? "" : next.contentRange());
            if (matcher.matches()) {
                chunkStart = Long.parseLong(matcher.group(1));
                if (!"*".equals(matcher.group(3))) {
                    totalLength = Long.parseLong(matcher.group(3));
                }
            } else if (next.status() == 200 && next.contentLength() != null) {
                chunkStart = position;
                totalLength = next.contentLength();
            } else {
                chunkStart = position;
            }
        }

        private void closeCurrent() throws IOException {
            if (current != null) {
                current.close();
                current = null;
            }
        }
    }

    private record EbmlVint(long value, int length, boolean unknown) {
    }

    private record EbmlElement(long id, long headerLength, long totalLength, boolean unknownSize) {
    }

    private record MatroskaPrefix(byte[] bytes, long nextOffset) {
    }

    public record RelayResponse(
            int status,
            String contentType,
            Long contentLength,
            String contentRange,
            String acceptRanges,
            boolean rangedRequest,
            InputStream body
    ) {
    }
}
