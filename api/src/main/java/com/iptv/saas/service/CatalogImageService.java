package com.iptv.saas.service;

import com.iptv.saas.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CatalogImageService {
    private static final List<String> IMAGE_FIELDS = List.of("poster", "logo", "backdrop", "image");

    private final HttpClient httpClient;
    private final int maxImageBytes;
    private final int maxCachedImages;
    private final Map<String, String> sources = new ConcurrentHashMap<>();
    private final Map<String, CachedImage> cache;

    public CatalogImageService(
            @Value("${app.iptv.image-proxy-max-bytes:8388608}") int maxImageBytes,
            @Value("${app.iptv.image-proxy-cache-items:256}") int maxCachedImages
    ) {
        this.maxImageBytes = Math.max(262_144, maxImageBytes);
        this.maxCachedImages = Math.max(16, maxCachedImages);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.cache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedImage> eldest) {
                return size() > CatalogImageService.this.maxCachedImages;
            }
        });
    }

    public void rewrite(Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> rewritten = (Map<String, Object>) rewriteValue(payload);
        payload.clear();
        payload.putAll(rewritten);
    }

    public CachedImage load(String key) {
        CachedImage cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        String source = sources.get(key);
        if (source == null) {
            throw ApiException.notFound("Image introuvable");
        }
        URI uri = publicImageUri(source);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(25))
                .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/gif,*/*;q=0.1")
                .header("User-Agent", "Nexora-IPTV/1.0")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw ApiException.notFound("Image distante indisponible");
                }
                String contentType = response.headers().firstValue("Content-Type")
                        .orElse("")
                        .split(";", 2)[0]
                        .strip()
                        .toLowerCase(Locale.ROOT);
                if (!isAllowedImageType(contentType)) {
                    throw ApiException.validation("Format d'image non autorise");
                }
                CachedImage image = new CachedImage(readLimited(input), contentType);
                cache.put(key, image);
                return image;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Chargement de l'image interrompu");
        } catch (IOException exception) {
            throw ApiException.serviceUnavailable("Impossible de charger l'image distante");
        }
    }

    private Object rewriteValue(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> rewritten = new LinkedHashMap<>();
            rawMap.forEach((key, nestedValue) -> {
                String field = String.valueOf(key);
                if (IMAGE_FIELDS.contains(field) && nestedValue instanceof String url) {
                    if (isRemoteHttpUrl(url)) {
                        rewritten.put(field, register(url));
                    } else if (isSafeApplicationImagePath(url)) {
                        rewritten.put(field, url);
                    } else {
                        rewritten.put(field, "");
                    }
                } else {
                    rewritten.put(field, rewriteValue(nestedValue));
                }
            });
            return rewritten;
        } else if (value instanceof Iterable<?> values) {
            List<Object> rewritten = new ArrayList<>();
            values.forEach(nestedValue -> rewritten.add(rewriteValue(nestedValue)));
            return rewritten;
        }
        return value;
    }

    private String register(String source) {
        String key = digest(source);
        sources.putIfAbsent(key, source);
        return "/api/catalog/images/" + key;
    }

    private URI publicImageUri(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new IllegalArgumentException();
                }
            }
            return uri;
        } catch (IOException | IllegalArgumentException exception) {
            throw ApiException.validation("URL d'image non autorisee");
        }
    }

    private boolean isRemoteHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isSafeApplicationImagePath(String value) {
        String normalized = value == null ? "" : value.strip();
        return normalized.startsWith("/")
                && !normalized.startsWith("//")
                && normalized.indexOf('\\') < 0;
    }

    private boolean isAllowedImageType(String contentType) {
        return contentType.equals("image/jpeg")
                || contentType.equals("image/png")
                || contentType.equals("image/webp")
                || contentType.equals("image/avif")
                || contentType.equals("image/gif");
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16_384];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxImageBytes) {
                throw new IOException("Image trop volumineuse");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 16; index++) {
                hex.append(String.format("%02x", hash[index]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponible", exception);
        }
    }

    public record CachedImage(byte[] bytes, String contentType) {
    }
}
