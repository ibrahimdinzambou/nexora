package com.iptv.saas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class XtreamCatalogService {
    private static final Logger LOGGER = LoggerFactory.getLogger(XtreamCatalogService.class);

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final Duration cacheTtl;
    private final Duration diskCacheMaxAge;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final Path diskCacheDirectory;
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<Long, Object> loadLocks = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> refreshing = new ConcurrentHashMap<>();
    private final Map<String, SeriesCacheEntry> seriesCache = new ConcurrentHashMap<>();
    private final Map<String, Object> seriesLoadLocks = new ConcurrentHashMap<>();
    private final ExecutorService catalogExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "xtream-catalog");
        thread.setDaemon(true);
        return thread;
    });

    public XtreamCatalogService(
            ObjectMapper mapper,
            @Value("${app.iptv.cache-ttl-seconds:900}") long cacheTtlSeconds,
            @Value("${app.iptv.xtream-timeout-seconds:45}") long timeoutSeconds,
            @Value("${app.iptv.xtream-max-response-bytes:134217728}") int maxResponseBytes,
            @Value("${app.iptv.xtream-cache-max-age-hours:168}") long diskCacheMaxAgeHours,
            @Value("${app.iptv.xtream-cache-directory:./data/xtream-cache}") String cacheDirectory
    ) {
        this.mapper = mapper;
        this.cacheTtl = Duration.ofSeconds(Math.max(30, cacheTtlSeconds));
        this.diskCacheMaxAge = Duration.ofHours(Math.max(1, diskCacheMaxAgeHours));
        this.requestTimeout = Duration.ofSeconds(Math.max(30, timeoutSeconds));
        this.maxResponseBytes = Math.max(1_048_576, maxResponseBytes);
        this.diskCacheDirectory = Path.of(cacheDirectory).toAbsolutePath().normalize();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public M3uPlaylistService.Playlist load(IptvAccount account) {
        validate(account);
        CacheEntry cached = cache.get(account.id);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.playlist();
        }

        Object lock = loadLocks.computeIfAbsent(account.id, ignored -> new Object());
        synchronized (lock) {
            cached = cache.get(account.id);
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
                return cached.playlist();
            }
            DiskCacheEntry disk = readDiskCache(account.id);
            if (disk != null) {
                cache.put(account.id, new CacheEntry(Instant.now().plus(cacheTtl), disk.playlist()));
                if (disk.savedAt().plus(diskCacheMaxAge).isBefore(Instant.now())) {
                    refreshInBackground(account);
                }
                return disk.playlist();
            }
            M3uPlaylistService.Playlist loaded = fetchCatalog(account);
            cache.put(account.id, new CacheEntry(Instant.now().plus(cacheTtl), loaded));
            writeDiskCache(account.id, loaded);
            return loaded;
        }
    }

    public boolean hasReusableCache(Long accountId) {
        if (accountId == null) {
            return false;
        }
        CacheEntry cached = cache.get(accountId);
        return cached != null && cached.expiresAt().isAfter(Instant.now())
                || Files.isRegularFile(cacheFile(accountId));
    }

    public M3uPlaylistService.Series loadSeries(IptvAccount account, String publicSeriesId) {
        validate(account);
        String cacheKey = account.id + "|" + publicSeriesId;
        SeriesCacheEntry cached = seriesCache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.series();
        }

        Object lock = seriesLoadLocks.computeIfAbsent(cacheKey, ignored -> new Object());
        synchronized (lock) {
            cached = seriesCache.get(cacheKey);
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
                return cached.series();
            }
            try {
                M3uPlaylistService.Series loaded = fetchSeries(account, publicSeriesId);
                seriesCache.put(cacheKey, new SeriesCacheEntry(Instant.now().plus(cacheTtl), loaded));
                return loaded;
            } catch (RuntimeException exception) {
                if (cached != null) {
                    LOGGER.warn(
                            "Fiche serie Xtream en cache utilisee pour le compte {} apres erreur fournisseur",
                            account.id
                    );
                    return cached.series();
                }
                throw exception;
            }
        }
    }

    public void invalidate(Long accountId) {
        if (accountId == null) {
            return;
        }
        cache.remove(accountId);
        refreshing.remove(accountId);
        seriesCache.keySet().removeIf(key -> key.startsWith(accountId + "|"));
        seriesLoadLocks.keySet().removeIf(key -> key.startsWith(accountId + "|"));
        try {
            Files.deleteIfExists(cacheFile(accountId));
        } catch (IOException exception) {
            LOGGER.warn("Cache Xtream non supprime pour le compte {}", accountId);
        }
    }

    @PreDestroy
    void shutdown() {
        catalogExecutor.shutdownNow();
    }

    public AccountLimits accountLimits(IptvAccount account) {
        validate(account);
        JsonNode root = requestAccountInfo(account);
        JsonNode userInfo = root.has("user_info") ? root.path("user_info") : root;
        int maxConnections = nonNegativeInt(userInfo, "max_connections", 0);
        int activeConnections = nonNegativeInt(userInfo, "active_cons", 0);
        if (maxConnections <= 0) {
            maxConnections = nonNegativeInt(userInfo, "max_connections ", 0);
        }
        return new AccountLimits(maxConnections, activeConnections);
    }

    private M3uPlaylistService.Series fetchSeries(IptvAccount account, String publicSeriesId) {
        M3uPlaylistService.Series summary = load(account).findSeries(publicSeriesId);
        String providerSeriesId = providerId(publicSeriesId, "xtream-series-" + account.id + "-");
        JsonNode response = request(account, "get_series_info", "series_id", providerSeriesId);
        JsonNode episodeGroups = response.path("episodes");
        List<M3uPlaylistService.Entry> episodes = new ArrayList<>();
        if (episodeGroups.isObject()) {
            episodeGroups.fields().forEachRemaining(group -> {
                if (!group.getValue().isArray()) {
                    return;
                }
                for (JsonNode episode : group.getValue()) {
                    String streamId = text(episode, "id");
                    if (streamId.isBlank()) {
                        continue;
                    }
                    int season = positiveInt(episode, "season", positiveInt(group.getKey(), 1));
                    int episodeNumber = positiveInt(episode, "episode_num", episodes.size() + 1);
                    String title = firstText(episode, "title", "name");
                    if (title.isBlank()) {
                        title = "Épisode " + episodeNumber;
                    }
                    String extension = extension(episode, "container_extension", "mp4");
                    String entryId = "xtream-" + account.id + "-series-"
                            + providerSeriesId + "_" + streamId;
                    episodes.add(new M3uPlaylistService.Entry(
                            entryId,
                            "",
                            summary.title() + " S%02dE%02d".formatted(season, episodeNumber),
                            "series",
                            summary.categoryId(),
                            summary.categoryName(),
                            firstText(episode.path("info"), "movie_image", "cover"),
                            streamUrl(account, "series", streamId, extension),
                            summary.id(),
                            summary.title(),
                            season,
                            episodeNumber,
                            title
                    ));
                }
            });
        }
        return new M3uPlaylistService.Series(
                summary.id(),
                summary.title(),
                summary.categoryId(),
                summary.categoryName(),
                summary.poster(),
                List.copyOf(episodes)
        );
    }

    private M3uPlaylistService.Playlist fetchCatalog(IptvAccount account) {
        Future<Map<String, String>> liveCategoriesFuture = catalogExecutor.submit(() ->
                categories(account, "get_live_categories"));
        Future<Map<String, String>> movieCategoriesFuture = catalogExecutor.submit(() ->
                categories(account, "get_vod_categories"));
        Future<Map<String, String>> seriesCategoriesFuture = catalogExecutor.submit(() ->
                categories(account, "get_series_categories"));

        Map<String, String> liveCategories = futureValue(liveCategoriesFuture, "categories live Xtream");
        Map<String, String> movieCategories = futureValue(movieCategoriesFuture, "categories VOD Xtream");
        Map<String, String> seriesCategories = futureValue(seriesCategoriesFuture, "categories series Xtream");

        List<M3uPlaylistService.Entry> entries = new ArrayList<>();
        List<M3uPlaylistService.Category> categoryPayloads = new ArrayList<>();
        addCategories(account.id, "live", liveCategories, categoryPayloads);
        addCategories(account.id, "movie", movieCategories, categoryPayloads);
        addCategories(account.id, "series", seriesCategories, categoryPayloads);

        Future<JsonNode> liveStreamsFuture = catalogExecutor.submit(() ->
                collection(account, "get_live_streams", liveCategories));
        Future<JsonNode> moviesFuture = catalogExecutor.submit(() ->
                collection(account, "get_vod_streams", movieCategories));
        Future<JsonNode> seriesFuture = catalogExecutor.submit(() ->
                collection(account, "get_series", seriesCategories));

        JsonNode liveStreams = futureValue(liveStreamsFuture, "directs Xtream");
        if (liveStreams.isArray()) {
            for (JsonNode stream : liveStreams) {
                String streamId = text(stream, "stream_id");
                if (streamId.isBlank()) {
                    continue;
                }
                String categoryId = text(stream, "category_id");
                entries.add(new M3uPlaylistService.Entry(
                        "xtream-" + account.id + "-live-" + streamId,
                        text(stream, "epg_channel_id"),
                        firstText(stream, "name"),
                        "live",
                        categoryId(account.id, "live", categoryId),
                        categoryName(liveCategories, categoryId),
                        firstText(stream, "stream_icon"),
                        streamUrl(account, "live", streamId, "ts"),
                        null,
                        null,
                        null,
                        null,
                        null
                ));
            }
        }

        JsonNode movies = futureValue(moviesFuture, "films Xtream");
        if (movies.isArray()) {
            for (JsonNode stream : movies) {
                String streamId = text(stream, "stream_id");
                if (streamId.isBlank()) {
                    continue;
                }
                String categoryId = text(stream, "category_id");
                String extension = extension(stream, "container_extension", "mp4");
                entries.add(new M3uPlaylistService.Entry(
                        "xtream-" + account.id + "-movie-" + streamId,
                        "",
                        firstText(stream, "name"),
                        "movie",
                        categoryId(account.id, "movie", categoryId),
                        categoryName(movieCategories, categoryId),
                        firstText(stream, "stream_icon"),
                        streamUrl(account, "movie", streamId, extension),
                        null,
                        null,
                        null,
                        null,
                        null
                ));
            }
        }

        List<M3uPlaylistService.Series> series = new ArrayList<>();
        JsonNode seriesList = futureValue(seriesFuture, "series Xtream");
        if (seriesList.isArray()) {
            for (JsonNode item : seriesList) {
                String seriesId = text(item, "series_id");
                if (seriesId.isBlank()) {
                    continue;
                }
                String categoryId = text(item, "category_id");
                series.add(new M3uPlaylistService.Series(
                        "xtream-series-" + account.id + "-" + seriesId,
                        firstText(item, "name"),
                        categoryId(account.id, "series", categoryId),
                        categoryName(seriesCategories, categoryId),
                        firstText(item, "cover", "cover_big"),
                        List.of()
                ));
            }
        }

        LOGGER.info(
                "Catalogue Xtream charge pour le compte {}: {} directs, {} films, {} series",
                account.id,
                entries.stream().filter(entry -> "live".equals(entry.type())).count(),
                entries.stream().filter(entry -> "movie".equals(entry.type())).count(),
                series.size()
        );
        return new M3uPlaylistService.Playlist(
                List.copyOf(entries),
                List.copyOf(categoryPayloads),
                List.copyOf(series)
        );
    }

    private <T> T futureValue(Future<T> future, String label) {
        try {
            return future.get(requestTimeout.toSeconds() + 5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Chargement " + label + " interrompu");
        } catch (ExecutionException exception) {
            throw ApiException.serviceUnavailable("Impossible de charger " + label);
        } catch (TimeoutException exception) {
            throw ApiException.serviceUnavailable("Le fournisseur Xtream tarde sur " + label);
        }
    }

    public boolean refreshInBackground(IptvAccount account) {
        if (account == null || account.id == null) {
            return false;
        }
        if (refreshing.putIfAbsent(account.id, Boolean.TRUE) != null) {
            return false;
        }
        catalogExecutor.execute(() -> {
            try {
                M3uPlaylistService.Playlist loaded = fetchCatalog(account);
                cache.put(account.id, new CacheEntry(Instant.now().plus(cacheTtl), loaded));
                writeDiskCache(account.id, loaded);
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "Refresh Xtream en arriere-plan ignore pour le compte {}: {}",
                        account.id,
                        exception.getMessage()
                );
            } finally {
                refreshing.remove(account.id);
            }
        });
        return true;
    }

    private Map<String, String> categories(IptvAccount account, String action) {
        Map<String, String> categories = new LinkedHashMap<>();
        JsonNode response = request(account, action, null, null);
        if (response.isArray()) {
            for (JsonNode category : response) {
                String id = text(category, "category_id");
                if (!id.isBlank()) {
                    categories.put(id, firstText(category, "category_name"));
                }
            }
        }
        return categories;
    }

    private JsonNode collection(
            IptvAccount account,
            String action,
            Map<String, String> categories
    ) {
        if ("get_vod_streams".equals(action)) {
            return collectionByCategory(account, action, categories.keySet().stream().toList());
        }
        try {
            return request(account, action, null, null);
        } catch (ApiException exception) {
            LOGGER.info(
                    "Nouvelle tentative Xtream par categorie pour {} sur le compte {}",
                    action,
                    account.id
            );
            return collectionByCategory(account, action, categories.keySet().stream().toList());
        }
    }

    private JsonNode collectionByCategory(
            IptvAccount account,
            String action,
            List<String> categoryIds
    ) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, Math.max(1, categoryIds.size())));
        try {
            List<Future<JsonNode>> futures = categoryIds.stream()
                    .map(categoryId -> executor.submit(() ->
                            request(account, action, "category_id", categoryId)))
                    .toList();
            ArrayNode merged = mapper.createArrayNode();
            for (Future<JsonNode> future : futures) {
                JsonNode response = future.get(requestTimeout.toSeconds() + 5, TimeUnit.SECONDS);
                if (response.isArray()) {
                    merged.addAll((ArrayNode) response);
                }
            }
            return merged;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Chargement Xtream interrompu");
        } catch (ExecutionException exception) {
            throw ApiException.serviceUnavailable("Impossible de charger une categorie Xtream");
        } catch (TimeoutException exception) {
            throw ApiException.serviceUnavailable("Le fournisseur Xtream a depasse le delai de reponse");
        } finally {
            executor.shutdownNow();
        }
    }

    private void addCategories(
            Long accountId,
            String type,
            Map<String, String> categories,
            List<M3uPlaylistService.Category> target
    ) {
        categories.forEach((id, name) -> target.add(new M3uPlaylistService.Category(
                categoryId(accountId, type, id),
                name,
                type
        )));
    }

    private JsonNode request(IptvAccount account, String action, String parameter, String value) {
        StringBuilder query = new StringBuilder()
                .append("username=").append(encode(account.username))
                .append("&password=").append(encode(account.password))
                .append("&action=").append(encode(action));
        if (parameter != null && value != null) {
            query.append("&").append(encode(parameter)).append("=").append(encode(value));
        }
        URI endpoint = URI.create(trimSlash(account.baseUrl) + "/player_api.php?" + query);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
                .GET()
                .build();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw ApiException.serviceUnavailable("Le fournisseur Xtream a refuse le catalogue");
                }
                if (response.body().length > maxResponseBytes) {
                    throw new IOException("Reponse Xtream trop volumineuse");
                }
                return mapper.readTree(response.body());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw ApiException.serviceUnavailable("Chargement Xtream interrompu");
            } catch (IOException | IllegalArgumentException exception) {
                if (attempt == 0) {
                    continue;
                }
                LOGGER.warn(
                        "Action Xtream {} impossible pour le compte {}: {}",
                        action,
                        account.id,
                        exception.getClass().getSimpleName()
                );
                throw ApiException.serviceUnavailable("Impossible de charger le catalogue Xtream");
            }
        }
        throw ApiException.serviceUnavailable("Impossible de charger le catalogue Xtream");
    }

    private JsonNode requestAccountInfo(IptvAccount account) {
        String query = "username=" + encode(account.username)
                + "&password=" + encode(account.password);
        URI endpoint = URI.create(trimSlash(account.baseUrl) + "/player_api.php?" + query);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw ApiException.serviceUnavailable("Le fournisseur Xtream a refuse les informations du compte");
            }
            if (response.body().length > maxResponseBytes) {
                throw new IOException("Reponse Xtream trop volumineuse");
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Synchronisation Xtream interrompue");
        } catch (IOException | IllegalArgumentException exception) {
            throw ApiException.serviceUnavailable("Impossible de synchroniser les limites Xtream");
        }
    }

    private DiskCacheEntry readDiskCache(Long accountId) {
        Path file = cacheFile(accountId);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(file))) {
            Object value = input.readObject();
            return value instanceof M3uPlaylistService.Playlist playlist
                    ? new DiskCacheEntry(playlist, Files.getLastModifiedTime(file).toInstant())
                    : null;
        } catch (IOException | ClassNotFoundException exception) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // A fresh provider response can replace the invalid cache later.
            }
            return null;
        }
    }

    private void writeDiskCache(Long accountId, M3uPlaylistService.Playlist playlist) {
        Path file = cacheFile(accountId);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(diskCacheDirectory);
            try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(temporary))) {
                output.writeObject(playlist);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOGGER.warn("Cache Xtream non ecrit pour le compte {}", accountId);
        }
    }

    private Path cacheFile(Long accountId) {
        return diskCacheDirectory.resolve("account-" + accountId + ".ser");
    }

    private void validate(IptvAccount account) {
        if (account == null || account.id == null || account.baseUrl == null
                || account.username == null || account.password == null) {
            throw ApiException.validation("Compte Xtream incomplet");
        }
    }

    public record AccountLimits(int maxConnections, int activeConnections) {
    }

    private String categoryId(Long accountId, String type, String providerCategoryId) {
        return "xtream-cat-" + accountId + "-" + type + "-" + providerCategoryId;
    }

    private String categoryName(Map<String, String> categories, String id) {
        return categories.getOrDefault(id, "Sans categorie");
    }

    private String streamUrl(IptvAccount account, String type, String streamId, String extension) {
        return trimSlash(account.baseUrl)
                + "/" + type
                + "/" + encodePath(account.username)
                + "/" + encodePath(account.password)
                + "/" + encodePath(streamId)
                + "." + extension;
    }

    private String providerId(String publicId, String prefix) {
        if (publicId == null || !publicId.startsWith(prefix)) {
            throw ApiException.validation("Identifiant Xtream invalide");
        }
        return publicId.substring(prefix.length());
    }

    private String extension(JsonNode node, String field, String fallback) {
        String value = text(node, field).toLowerCase();
        return value.matches("[a-z0-9]{2,5}") ? value : fallback;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").strip();
    }

    private int positiveInt(JsonNode node, String field, int fallback) {
        return positiveInt(text(node, field), fallback);
    }

    private int positiveInt(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private int nonNegativeInt(JsonNode node, String field, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(text(node, field)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String trimSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String encodePath(String value) {
        return encode(value).replace("+", "%20");
    }

    private record CacheEntry(Instant expiresAt, M3uPlaylistService.Playlist playlist) {
    }

    private record SeriesCacheEntry(Instant expiresAt, M3uPlaylistService.Series series) {
    }

    private record DiskCacheEntry(M3uPlaylistService.Playlist playlist, Instant savedAt) {
    }
}
