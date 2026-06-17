package com.iptv.saas.service;

import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class M3uPlaylistService {
    private static final Logger LOGGER = LoggerFactory.getLogger(M3uPlaylistService.class);
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    private static final Pattern SERIES_SE_PATTERN = Pattern.compile(
            "(?i)^(.*?)(?:\\s*[-–—:|]?\\s*)S(?:AISON)?\\s*0*(\\d{1,3})\\s*[._\\- ]*E(?:P(?:ISODE)?)?\\s*0*(\\d{1,4})(?:\\s*[-–—:|]\\s*(.*))?$"
    );
    private static final Pattern SERIES_X_PATTERN = Pattern.compile(
            "(?i)^(.*?)(?:\\s*[-–—:|]?\\s*)0*(\\d{1,3})\\s*[xX]\\s*0*(\\d{1,4})(?:\\s*[-–—:|]\\s*(.*))?$"
    );
    private static final Pattern LEADING_RANK_PATTERN = Pattern.compile("^#\\s*\\d+\\s*[-.:)]?\\s*");
    private static final Pattern RELEASE_YEAR_PATTERN = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?!\\d)");

    private final HttpClient httpClient;
    private final Duration cacheTtl;
    private final Duration diskCacheMaxAge;
    private final Duration readTimeout;
    private final int maxPlaylistBytes;
    private final Path diskCacheDirectory;
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<Long, Object> loadLocks = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> refreshing = new ConcurrentHashMap<>();
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "m3u-cache-io");
        thread.setDaemon(true);
        return thread;
    });

    public M3uPlaylistService(
            @Value("${app.iptv.cache-ttl-seconds:900}") long cacheTtlSeconds,
            @Value("${app.iptv.m3u-max-playlist-bytes:134217728}") int maxPlaylistBytes,
            @Value("${app.iptv.m3u-cache-directory:./data/m3u-cache}") String diskCacheDirectory,
            @Value("${app.iptv.m3u-cache-max-age-hours:168}") long diskCacheMaxAgeHours,
            @Value("${app.iptv.m3u-read-timeout-seconds:180}") long readTimeoutSeconds
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.cacheTtl = Duration.ofSeconds(Math.max(30, cacheTtlSeconds));
        this.diskCacheMaxAge = Duration.ofHours(Math.max(1, diskCacheMaxAgeHours));
        this.readTimeout = Duration.ofSeconds(Math.max(30, readTimeoutSeconds));
        this.maxPlaylistBytes = Math.max(1_048_576, maxPlaylistBytes);
        this.diskCacheDirectory = Path.of(diskCacheDirectory).toAbsolutePath().normalize();
    }

    public Playlist load(IptvAccount account) {
        if (account == null || account.id == null) {
            throw ApiException.validation("Compte M3U invalide");
        }

        CacheEntry cached = cache.get(account.id);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.playlist();
        }

        Object loadLock = loadLocks.computeIfAbsent(account.id, ignored -> new Object());
        synchronized (loadLock) {
            cached = cache.get(account.id);
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
                return cached.playlist();
            }

            DiskCacheEntry diskCached = readDiskCache(account.id);
            if (diskCached != null) {
                cache.put(account.id, new CacheEntry(Instant.now().plus(cacheTtl), diskCached.playlist()));
                if (diskCached.savedAt().plus(diskCacheMaxAge).isBefore(Instant.now())) {
                    refreshInBackground(account);
                }
                return diskCached.playlist();
            }

            Playlist playlist = fetch(account);
            cache.put(account.id, new CacheEntry(Instant.now().plus(cacheTtl), playlist));
            return playlist;
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

    public void invalidate(Long accountId) {
        if (accountId != null) {
            cache.remove(accountId);
            try {
                Files.deleteIfExists(cacheFile(accountId));
            } catch (IOException ignored) {
                // A future refresh can replace a cache file that could not be deleted now.
            }
        }
    }

    @PreDestroy
    void shutdown() {
        ioExecutor.shutdownNow();
    }

    Playlist parse(IptvAccount account, String content) {
        List<Entry> entries = new ArrayList<>();
        Map<String, Category> categories = new LinkedHashMap<>();
        String pendingMetadata = null;

        for (String rawLine : content.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXTINF")) {
                pendingMetadata = line;
                continue;
            }
            if (line.startsWith("#") || pendingMetadata == null) {
                continue;
            }

            String streamUrl = stripStreamOptions(line);
            if (!isHttpUrl(streamUrl)) {
                pendingMetadata = null;
                continue;
            }

            Map<String, String> attributes = attributes(pendingMetadata);
            String name = entryName(pendingMetadata, attributes);
            String group = attributes.getOrDefault("group-title", "Sans catégorie").strip();
            if (group.isBlank()) {
                group = "Sans catégorie";
            }
            String type = inferType(streamUrl, name);
            String categoryId = "m3u-cat-" + account.id + "-" + digest(type + "|" + group);
            String itemId = "m3u-" + account.id + "-" + digest(streamUrl);
            String tvgId = attributes.getOrDefault("tvg-id", "").strip();
            String logo = attributes.getOrDefault("tvg-logo", "").strip();
            SeriesMetadata series = "series".equals(type)
                    ? seriesMetadata(account.id, name, categoryId)
                    : null;

            Entry entry = new Entry(
                    itemId,
                    tvgId,
                    name,
                    type,
                    categoryId,
                    group,
                    logo,
                    streamUrl,
                    series == null ? null : series.id(),
                    series == null ? null : series.title(),
                    series == null ? null : series.season(),
                    series == null ? null : series.episode(),
                    series == null ? null : series.episodeTitle()
            );
            entries.add(entry);
            categories.putIfAbsent(type + "|" + categoryId, new Category(categoryId, group, type));
            pendingMetadata = null;
        }

        if (entries.isEmpty()) {
            throw ApiException.serviceUnavailable("La playlist M3U ne contient aucun programme exploitable");
        }
        return new Playlist(
                List.copyOf(entries),
                List.copyOf(categories.values()),
                buildSeries(entries)
        );
    }

    private Playlist fetch(IptvAccount account) {
        String playlistUrl = account.playlistUrl == null ? "" : account.playlistUrl.strip();
        if (!isHttpUrl(playlistUrl)) {
            throw ApiException.validation("URL de playlist M3U invalide");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(playlistUrl))
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "application/x-mpegURL, audio/x-mpegurl, text/plain, */*")
                .header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw ApiException.serviceUnavailable("Le fournisseur M3U a refusé la playlist");
            }
            try (InputStream input = response.body()) {
                byte[] bytes = readLimitedWithTimeout(input);
                Playlist playlist = parse(account, new String(bytes, StandardCharsets.UTF_8));
                writeDiskCache(account.id, playlist);
                return playlist;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Chargement de la playlist M3U interrompu");
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.warn(
                    "Echec de lecture M3U pour le compte {}: {}: {}",
                    account.id,
                    exception.getClass().getSimpleName(),
                    exception.getMessage()
            );
            throw ApiException.serviceUnavailable("Impossible de charger la playlist M3U");
        }
    }

    private byte[] readLimitedWithTimeout(InputStream input) throws IOException, InterruptedException {
        Future<byte[]> read = ioExecutor.submit(() -> readLimited(input));
        try {
            return read.get(readTimeout.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            read.cancel(true);
            input.close();
            throw new IOException("Lecture de la playlist expiree", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("Lecture de la playlist impossible", cause);
        }
    }

    public boolean refreshInBackground(IptvAccount account) {
        if (account == null || account.id == null) {
            return false;
        }
        if (refreshing.putIfAbsent(account.id, Boolean.TRUE) != null) {
            return false;
        }
        ioExecutor.execute(() -> {
            try {
                Playlist playlist = fetch(account);
                cache.put(account.id, new CacheEntry(Instant.now().plus(cacheTtl), playlist));
            } catch (RuntimeException ignored) {
                // Keep serving the disk cache while the provider is unavailable.
            } finally {
                refreshing.remove(account.id);
            }
        });
        return true;
    }

    private DiskCacheEntry readDiskCache(Long accountId) {
        Path file = cacheFile(accountId);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(file))) {
            Object value = input.readObject();
            if (value instanceof Playlist playlist) {
                return new DiskCacheEntry(playlist, Files.getLastModifiedTime(file).toInstant());
            }
        } catch (IOException | ClassNotFoundException exception) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // A corrupt cache will simply be ignored again on the next request.
            }
        }
        return null;
    }

    private void writeDiskCache(Long accountId, Playlist playlist) {
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
            LOGGER.warn(
                    "Cache M3U non ecrit pour le compte {}: {}",
                    accountId,
                    exception.getClass().getSimpleName()
            );
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupIgnored) {
                // The in-memory cache remains usable even if disk persistence fails.
            }
        }
    }

    private Path cacheFile(Long accountId) {
        return diskCacheDirectory.resolve("account-" + accountId + ".ser");
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16_384];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxPlaylistBytes) {
                throw ApiException.validation("La playlist M3U dépasse la taille autorisée");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private Map<String, String> attributes(String metadata) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE_PATTERN.matcher(metadata);
        while (matcher.find()) {
            values.put(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2));
        }
        return values;
    }

    private String entryName(String metadata, Map<String, String> attributes) {
        int delimiter = metadataDelimiter(metadata);
        String name = delimiter >= 0 ? metadata.substring(delimiter + 1).strip() : "";
        if (name.isBlank()) {
            name = attributes.getOrDefault("tvg-name", "Programme sans titre").strip();
        }
        return name.isBlank() ? "Programme sans titre" : name;
    }

    private int metadataDelimiter(String metadata) {
        boolean quoted = false;
        for (int index = 0; index < metadata.length(); index++) {
            char current = metadata.charAt(index);
            if (current == '"') {
                quoted = !quoted;
            } else if (current == ',' && !quoted) {
                return index;
            }
        }
        return -1;
    }

    private String inferType(String streamUrl, String name) {
        String path = URI.create(streamUrl).getPath().toLowerCase(Locale.ROOT);
        if (path.contains("/series/")) {
            return "series";
        }
        boolean videoOnDemand = path.contains("/movie/")
                || path.endsWith(".mp4")
                || path.endsWith(".mkv")
                || path.endsWith(".avi");
        if (videoOnDemand && looksLikeSeriesEpisode(name)) {
            return "series";
        }
        if (videoOnDemand) {
            return "movie";
        }
        return "live";
    }

    private boolean looksLikeSeriesEpisode(String name) {
        Matcher matcher = SERIES_SE_PATTERN.matcher(name == null ? "" : name);
        if (matcher.matches() && !cleanSeriesTitle(matcher.group(1)).isBlank()) {
            return true;
        }
        matcher = SERIES_X_PATTERN.matcher(name == null ? "" : name);
        return matcher.matches() && !cleanSeriesTitle(matcher.group(1)).isBlank();
    }

    private SeriesMetadata seriesMetadata(Long accountId, String name, String categoryId) {
        Matcher matcher = SERIES_SE_PATTERN.matcher(name);
        if (!matcher.matches()) {
            matcher = SERIES_X_PATTERN.matcher(name);
        }

        String title;
        int season;
        int episode;
        String episodeTitle;
        if (matcher.matches()) {
            title = cleanSeriesTitle(matcher.group(1));
            season = parsePositiveNumber(matcher.group(2), 1);
            episode = parsePositiveNumber(matcher.group(3), 1);
            episodeTitle = matcher.group(4) == null ? "" : matcher.group(4).strip();
        } else {
            title = cleanSeriesTitle(name);
            season = 1;
            episode = 1;
            episodeTitle = "";
        }

        if (title.isBlank()) {
            title = name.strip();
        }
        if (episodeTitle.isBlank()) {
            episodeTitle = "Épisode " + episode;
        }
        String seriesId = "m3u-series-" + accountId + "-" + digest(categoryId + "|" + normalizeKey(title));
        return new SeriesMetadata(seriesId, title, season, episode, episodeTitle);
    }

    private String cleanSeriesTitle(String value) {
        String title = value == null ? "" : value.strip();
        title = LEADING_RANK_PATTERN.matcher(title).replaceFirst("");
        return title.replaceAll("[\\s\\-–—:|]+$", "").strip();
    }

    private String normalizeKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
    }

    private int parsePositiveNumber(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    static int releaseYear(String value) {
        Matcher matcher = RELEASE_YEAR_PATTERN.matcher(value == null ? "" : value);
        int year = 0;
        while (matcher.find()) {
            year = Integer.parseInt(matcher.group(1));
        }
        return year;
    }

    private List<Series> buildSeries(List<Entry> entries) {
        Map<String, List<Entry>> grouped = new LinkedHashMap<>();
        entries.stream()
                .filter(entry -> "series".equals(entry.type()))
                .forEach(entry -> grouped.computeIfAbsent(entry.seriesId(), ignored -> new ArrayList<>()).add(entry));

        return grouped.values().stream()
                .map(group -> {
                    Entry first = group.get(0);
                    String poster = group.stream()
                            .map(Entry::logo)
                            .filter(value -> value != null && !value.isBlank())
                            .findFirst()
                            .orElse("");
                    List<Entry> sortedEpisodes = group.stream()
                            .sorted(Comparator
                                    .comparing(Entry::seasonNumber)
                                    .thenComparing(Entry::episodeNumber)
                                    .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER))
                            .toList();
                    return new Series(
                            first.seriesId(),
                            first.seriesTitle(),
                            first.categoryId(),
                            first.categoryName(),
                            poster,
                            sortedEpisodes
                    );
                })
                .sorted(Comparator.comparing(Series::title, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private String stripStreamOptions(String value) {
        int options = value.indexOf('|');
        return (options >= 0 ? value.substring(0, options) : value).strip();
    }

    private boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 12; index++) {
                hex.append(String.format("%02x", hash[index]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponible", exception);
        }
    }

    public record Playlist(List<Entry> entries, List<Category> categories, List<Series> series)
            implements Serializable {
        public Entry find(String itemId) {
            return entries.stream()
                    .filter(entry -> entry.id().equals(itemId))
                    .findFirst()
                    .orElseThrow(() -> ApiException.notFound("Programme M3U introuvable"));
        }

        public Series findSeries(String seriesId) {
            return series.stream()
                    .filter(item -> item.id().equals(seriesId))
                    .findFirst()
                    .orElseThrow(() -> ApiException.notFound("Série M3U introuvable"));
        }
    }

    public record Entry(
            String id,
            String tvgId,
            String name,
            String type,
            String categoryId,
            String categoryName,
            String logo,
            String streamUrl,
            String seriesId,
            String seriesTitle,
            Integer seasonNumber,
            Integer episodeNumber,
            String episodeTitle
    ) implements Serializable {
        public Map<String, Object> apiPayload() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("name", name);
            item.put("type", type);
            item.put("categoryId", categoryId);
            item.put("categoryName", categoryName);
            if ("live".equals(type)) {
                item.put("logo", logo);
            } else {
                item.put("poster", logo);
            }
            if ("movie".equals(type)) {
                int releaseYear = M3uPlaylistService.releaseYear(name);
                if (releaseYear > 0) {
                    item.put("releaseYear", releaseYear);
                }
            }
            if ("series".equals(type)) {
                item.put("seriesId", seriesId);
                item.put("seriesTitle", seriesTitle);
                item.put("season", seasonNumber);
                item.put("episode", episodeNumber);
                item.put("episodeTitle", episodeTitle);
                item.put("isEpisode", true);
            }
            return item;
        }
    }

    public record Series(
            String id,
            String title,
            String categoryId,
            String categoryName,
            String poster,
            List<Entry> episodes
    ) implements Serializable {
        public boolean matches(String query) {
            if (query == null || query.isBlank()) {
                return true;
            }
            String normalized = query.toLowerCase(Locale.ROOT);
            return title.toLowerCase(Locale.ROOT).contains(normalized)
                    || episodes.stream().anyMatch(entry -> entry.name().toLowerCase(Locale.ROOT).contains(normalized));
        }

        public boolean matchesNormalized(String normalizedQuery) {
            if (normalizedQuery == null || normalizedQuery.isBlank()) {
                return true;
            }
            return normalizeSearchText(title).contains(normalizedQuery)
                    || episodes.stream().anyMatch(entry -> normalizeSearchText(entry.name()).contains(normalizedQuery));
        }

        public Map<String, Object> apiPayload() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("name", title);
            item.put("type", "series");
            item.put("categoryId", categoryId);
            item.put("categoryName", categoryName);
            item.put("poster", poster);
            item.put("seasonCount", seasonCount());
            item.put("episodeCount", episodes.size());
            item.put("isSeries", true);
            return item;
        }

        public Map<String, Object> detailPayload() {
            Map<Integer, List<Entry>> bySeason = new TreeMap<>();
            episodes.forEach(entry -> bySeason
                    .computeIfAbsent(entry.seasonNumber(), ignored -> new ArrayList<>())
                    .add(entry));

            List<Map<String, Object>> seasons = bySeason.entrySet().stream()
                    .map(entry -> {
                        List<Map<String, Object>> episodePayloads = entry.getValue().stream()
                                .map(episode -> {
                                    Map<String, Object> payload = new LinkedHashMap<>(episode.apiPayload());
                                    payload.put("name", episode.episodeTitle());
                                    return payload;
                                })
                                .toList();
                        Map<String, Object> season = new LinkedHashMap<>();
                        season.put("season", entry.getKey());
                        season.put("name", "Saison " + entry.getKey());
                        season.put("episodeCount", episodePayloads.size());
                        season.put("episodes", episodePayloads);
                        return season;
                    })
                    .toList();

            Map<String, Object> body = new LinkedHashMap<>(apiPayload());
            body.put("seasons", seasons);
            return body;
        }

        private long seasonCount() {
            return episodes.stream().map(Entry::seasonNumber).distinct().count();
        }

        private static String normalizeSearchText(String value) {
            return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "")
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", " ")
                    .strip();
        }
    }

    public record Category(String id, String name, String type) implements Serializable {
        public Map<String, Object> apiPayload() {
            return Map.of("id", id, "name", name, "type", type);
        }
    }

    private record SeriesMetadata(String id, String title, int season, int episode, String episodeTitle) {
    }

    private record CacheEntry(Instant expiresAt, Playlist playlist) {
    }

    private record DiskCacheEntry(Playlist playlist, Instant savedAt) {
    }
}
