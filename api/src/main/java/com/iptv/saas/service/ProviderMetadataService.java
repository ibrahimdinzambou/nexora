package com.iptv.saas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.domain.IptvAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ProviderMetadataService {
    private static final int MAX_RESPONSE_BYTES = 6 * 1024 * 1024;
    private static final Pattern YEAR_PATTERN = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?!\\d)");
    private static final Pattern CLOCK_DURATION = Pattern.compile("^(?:(\\d+):)?(\\d{1,2}):(\\d{2})$");

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Duration cacheTtl;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public ProviderMetadataService(
            ObjectMapper mapper,
            @Value("${app.iptv.metadata-timeout-seconds:20}") long timeoutSeconds,
            @Value("${app.iptv.metadata-cache-ttl-minutes:60}") long cacheTtlMinutes
    ) {
        this.mapper = mapper;
        this.requestTimeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        this.cacheTtl = Duration.ofMinutes(Math.max(5, cacheTtlMinutes));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, Math.max(3, timeoutSeconds))))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> movieDetails(IptvAccount account, M3uPlaylistService.Entry entry) {
        Map<String, Object> details = new LinkedHashMap<>(entry.apiPayload());
        details.put("isDetail", true);

        providerCoordinates(entry.streamUrl()).ifPresent(coordinates -> {
            String streamId = coordinates.streamId();
            JsonNode response = providerJson(
                    account.id,
                    coordinates,
                    "get_vod_info",
                    "vod_id",
                    streamId
            ).orElse(null);
            if (response == null) {
                return;
            }
            JsonNode info = response.path("info");
            JsonNode movieData = response.path("movie_data");
            applyCommonMetadata(details, info, movieData);
        });

        applyCatalogFallbacks(details, entry.name(), entry.categoryName(), entry.logo());
        return details;
    }

    public Map<String, Object> seriesDetails(IptvAccount account, M3uPlaylistService.Series series) {
        Map<String, Object> details = new LinkedHashMap<>(series.detailPayload());
        M3uPlaylistService.Entry firstEpisode = series.episodes().stream().findFirst().orElse(null);
        if (firstEpisode == null) {
            applyCatalogFallbacks(details, series.title(), series.categoryName(), series.poster());
            return details;
        }

        providerCoordinates(firstEpisode.streamUrl()).ifPresent(coordinates -> {
            JsonNode providerSeries = findProviderSeries(account.id, coordinates, series).orElse(null);
            if (providerSeries == null) {
                return;
            }

            String seriesId = firstText(providerSeries, "series_id");
            JsonNode fullResponse = seriesId == null
                    ? null
                    : providerJson(account.id, coordinates, "get_series_info", "series_id", seriesId).orElse(null);
            JsonNode info = fullResponse != null && fullResponse.path("info").isObject()
                    ? fullResponse.path("info")
                    : providerSeries;

            applyCommonMetadata(details, info, providerSeries);
            if (fullResponse != null) {
                enrichEpisodes(details, fullResponse.path("episodes"));
            }
        });

        applyCatalogFallbacks(details, series.title(), series.categoryName(), series.poster());
        return details;
    }

    public void invalidate(Long accountId) {
        if (accountId == null) {
            return;
        }
        String prefix = accountId + ":";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private Optional<JsonNode> findProviderSeries(
            Long accountId,
            ProviderCoordinates coordinates,
            M3uPlaylistService.Series series
    ) {
        Optional<JsonNode> response = providerJson(accountId, coordinates, "get_series", null, null);
        if (response.isEmpty() || !response.get().isArray()) {
            return Optional.empty();
        }

        String expectedTitle = normalizedTitle(series.title());
        String expectedPoster = normalizedUrl(series.poster());
        JsonNode titleMatch = null;
        for (JsonNode candidate : response.get()) {
            if (!expectedPoster.isBlank()) {
                String providerPoster = normalizedUrl(firstText(candidate, "cover", "poster"));
                if (expectedPoster.equals(providerPoster)) {
                    return Optional.of(candidate);
                }
            }
            if (expectedTitle.equals(normalizedTitle(firstText(candidate, "name")))) {
                titleMatch = candidate;
            }
        }
        return Optional.ofNullable(titleMatch);
    }

    private void applyCommonMetadata(Map<String, Object> target, JsonNode primary, JsonNode secondary) {
        putText(target, "summary", firstText(primary, secondary, "plot", "description", "overview", "comment"));
        putText(target, "releaseDate", firstText(primary, secondary, "releasedate", "releaseDate", "release_date"));
        putText(target, "country", firstText(primary, secondary, "country"));
        putText(target, "ageRating", firstText(primary, secondary, "age", "mpaa_rating"));
        putText(target, "trailer", firstText(primary, secondary, "youtube_trailer", "trailer"));

        List<String> genres = valueList(firstNode(primary, secondary, "genre", "genres"));
        if (!genres.isEmpty()) {
            target.put("genres", genres);
        }
        List<String> cast = valueList(firstNode(primary, secondary, "cast", "actors"));
        if (!cast.isEmpty()) {
            target.put("cast", cast);
        }
        List<String> directors = valueList(firstNode(primary, secondary, "director"));
        if (!directors.isEmpty()) {
            target.put("directors", directors);
        }

        String rating = firstText(primary, secondary, "rating", "rating_5based", "vote_average");
        if (rating != null && !rating.equals("0") && !rating.equals("0.0")) {
            target.put("rating", normalizeRating(rating));
        }

        String duration = normalizedDuration(primary, secondary);
        if (duration != null) {
            target.put("duration", duration);
        }

        String poster = firstText(primary, secondary, "cover_big", "movie_image", "cover", "poster");
        if (isHttpUrl(poster)) {
            target.put("poster", poster);
        }
        String backdrop = firstImage(firstNode(primary, secondary, "backdrop_path", "backdrop", "backdrop_url"));
        if (isHttpUrl(backdrop)) {
            target.put("backdrop", backdrop);
        }
    }

    private void applyCatalogFallbacks(
            Map<String, Object> target,
            String title,
            String categoryName,
            String artwork
    ) {
        if (!target.containsKey("releaseYear")) {
            int releaseYear = releaseYear(firstTextValue(target.get("releaseDate"), title));
            if (releaseYear > 0) {
                target.put("releaseYear", releaseYear);
            }
        }
        if (!target.containsKey("genres")) {
            List<String> categories = categoryGenres(categoryName);
            if (!categories.isEmpty()) {
                target.put("genres", categories);
            }
        }
        if (!target.containsKey("poster") && isHttpUrl(artwork)) {
            target.put("poster", artwork);
        }
        target.put("metadataAvailable", hasUsefulMetadata(target));
    }

    @SuppressWarnings("unchecked")
    private void enrichEpisodes(Map<String, Object> details, JsonNode providerEpisodes) {
        if (!providerEpisodes.isObject() || !(details.get("seasons") instanceof List<?> seasons)) {
            return;
        }

        for (Object seasonValue : seasons) {
            if (!(seasonValue instanceof Map<?, ?> rawSeason)) {
                continue;
            }
            Map<String, Object> season = (Map<String, Object>) rawSeason;
            int seasonNumber = intValue(season.get("season"), 1);
            JsonNode providerSeason = providerEpisodes.path(String.valueOf(seasonNumber));
            if (!providerSeason.isArray() || !(season.get("episodes") instanceof List<?> episodes)) {
                continue;
            }

            for (Object episodeValue : episodes) {
                if (!(episodeValue instanceof Map<?, ?> rawEpisode)) {
                    continue;
                }
                Map<String, Object> episode = (Map<String, Object>) rawEpisode;
                int episodeNumber = intValue(episode.get("episode"), 1);
                JsonNode providerEpisode = findEpisode(providerSeason, episodeNumber);
                if (providerEpisode == null) {
                    continue;
                }
                JsonNode info = providerEpisode.path("info");
                String title = firstText(providerEpisode, "title", "name");
                if (title != null && !title.matches("(?i)^episode\\s+\\d+$")) {
                    episode.put("name", title);
                }
                putText(episode, "summary", firstText(info, providerEpisode, "plot", "description"));
                putText(episode, "releaseDate", firstText(info, providerEpisode, "releasedate", "releaseDate"));
                String duration = normalizedDuration(info, providerEpisode);
                if (duration != null) {
                    episode.put("duration", duration);
                }
                String rating = firstText(info, providerEpisode, "rating", "rating_5based");
                if (rating != null && !rating.equals("0") && !rating.equals("0.0")) {
                    episode.put("rating", normalizeRating(rating));
                }
                String poster = firstText(info, providerEpisode, "movie_image", "cover", "poster");
                if (isHttpUrl(poster)) {
                    episode.put("poster", poster);
                }
            }
        }
    }

    private JsonNode findEpisode(JsonNode providerSeason, int episodeNumber) {
        for (JsonNode candidate : providerSeason) {
            int number = candidate.path("episode_num").asInt(candidate.path("episode").asInt(-1));
            if (number == episodeNumber) {
                return candidate;
            }
        }
        return null;
    }

    private Optional<JsonNode> providerJson(
            Long accountId,
            ProviderCoordinates coordinates,
            String action,
            String parameter,
            String value
    ) {
        String cacheKey = accountId + ":" + action + ":" + (value == null ? "" : value);
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.value();
        }

        Optional<JsonNode> result = requestJson(coordinates, action, parameter, value);
        cache.put(cacheKey, new CacheEntry(Instant.now().plus(cacheTtl), result));
        return result;
    }

    private Optional<JsonNode> requestJson(
            ProviderCoordinates coordinates,
            String action,
            String parameter,
            String value
    ) {
        StringBuilder query = new StringBuilder()
                .append("username=").append(encode(coordinates.username()))
                .append("&password=").append(encode(coordinates.password()))
                .append("&action=").append(encode(action));
        if (parameter != null && value != null) {
            query.append("&").append(encode(parameter)).append("=").append(encode(value));
        }

        URI endpoint;
        try {
            endpoint = URI.create(coordinates.baseUrl() + "/player_api.php?" + query);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", "Nexora-IPTV/1.0")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return Optional.empty();
                }
                byte[] bytes = readLimited(input);
                JsonNode root = mapper.readTree(bytes);
                if (root == null || root.isNull() || (root.isArray() && root.isEmpty())) {
                    return Optional.empty();
                }
                return Optional.of(root);
            }
        } catch (IOException | IllegalArgumentException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16_384];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) {
                throw new IOException("Provider metadata response is too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private Optional<ProviderCoordinates> providerCoordinates(String streamUrl) {
        try {
            URI stream = URI.create(streamUrl);
            String[] segments = stream.getRawPath().split("/");
            if (segments.length < 5 || stream.getScheme() == null || stream.getRawAuthority() == null) {
                return Optional.empty();
            }
            String type = decode(segments[1]);
            if (!type.equals("movie") && !type.equals("series")) {
                return Optional.empty();
            }
            String username = decode(segments[2]);
            String password = decode(segments[3]);
            String fileName = decode(segments[segments.length - 1]);
            int extension = fileName.lastIndexOf('.');
            String streamId = extension > 0 ? fileName.substring(0, extension) : fileName;
            if (username.isBlank() || password.isBlank() || streamId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new ProviderCoordinates(
                    stream.getScheme() + "://" + stream.getRawAuthority(),
                    username,
                    password,
                    streamId
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private JsonNode firstNode(JsonNode primary, JsonNode secondary, String... fields) {
        JsonNode value = firstNode(primary, fields);
        return hasValue(value) ? value : firstNode(secondary, fields);
    }

    private JsonNode firstNode(JsonNode source, String... fields) {
        if (source == null || !source.isObject()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = source.get(field);
            if (hasValue(value)) {
                return value;
            }
        }
        return null;
    }

    private String firstText(JsonNode source, String... fields) {
        return textValue(firstNode(source, fields));
    }

    private String firstText(JsonNode primary, JsonNode secondary, String... fields) {
        return textValue(firstNode(primary, secondary, fields));
    }

    private String textValue(JsonNode node) {
        if (!hasValue(node) || node.isContainerNode()) {
            return null;
        }
        String value = node.asText().strip();
        return value.isBlank() || value.equalsIgnoreCase("null") ? null : value;
    }

    private boolean hasValue(JsonNode value) {
        return value != null && !value.isNull()
                && !(value.isTextual() && value.asText().isBlank())
                && !(value.isArray() && value.isEmpty());
    }

    private List<String> valueList(JsonNode value) {
        if (!hasValue(value)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(item -> addListValue(values, item.asText()));
        } else {
            String text = value.asText();
            for (String part : text.split("\\s*[,|]\\s*")) {
                addListValue(values, part);
            }
        }
        return values.stream().distinct().limit(16).toList();
    }

    private void addListValue(List<String> values, String rawValue) {
        String value = rawValue == null ? "" : rawValue.strip();
        if (!value.isBlank() && !value.equalsIgnoreCase("null")) {
            values.add(value);
        }
    }

    private String firstImage(JsonNode value) {
        if (!hasValue(value)) {
            return null;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                String image = textValue(item);
                if (isHttpUrl(image)) {
                    return image;
                }
            }
            return null;
        }
        return textValue(value);
    }

    private String normalizedDuration(JsonNode primary, JsonNode secondary) {
        String clock = firstText(primary, secondary, "duration");
        if (clock != null) {
            Matcher matcher = CLOCK_DURATION.matcher(clock);
            if (matcher.matches()) {
                int hours = matcher.group(1) == null ? 0 : Integer.parseInt(matcher.group(1));
                int minutes = Integer.parseInt(matcher.group(2));
                int seconds = Integer.parseInt(matcher.group(3));
                return humanDuration(hours * 3600L + minutes * 60L + seconds);
            }
            if (clock.matches("\\d+")) {
                return humanDuration(Long.parseLong(clock) * 60L);
            }
        }

        String seconds = firstText(primary, secondary, "duration_secs");
        if (seconds != null && seconds.matches("\\d+(?:\\.\\d+)?")) {
            return humanDuration((long) Double.parseDouble(seconds));
        }
        String minutes = firstText(primary, secondary, "episode_run_time", "runtime");
        if (minutes != null && minutes.matches("\\d+(?:\\.\\d+)?")) {
            return humanDuration((long) (Double.parseDouble(minutes) * 60L));
        }
        return null;
    }

    private String humanDuration(long seconds) {
        long totalMinutes = Math.max(1, Math.round(seconds / 60.0));
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours == 0) {
            return totalMinutes + " min";
        }
        return minutes == 0 ? hours + " h" : hours + " h " + minutes + " min";
    }

    private String normalizeRating(String value) {
        try {
            double rating = Double.parseDouble(value.replace(',', '.'));
            return rating == Math.rint(rating)
                    ? String.format(Locale.ROOT, "%.0f", rating)
                    : String.format(Locale.ROOT, "%.1f", rating);
        } catch (NumberFormatException exception) {
            return value;
        }
    }

    private List<String> categoryGenres(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : categoryName.split("\\s*[|/]+\\s*")) {
            String value = part.strip();
            if (!value.isBlank() && !value.matches("(?i)films?|movies?|series?|vod|\\d{4}")) {
                values.add(value);
            }
        }
        return values.stream().distinct().limit(4).toList();
    }

    private boolean hasUsefulMetadata(Map<String, Object> target) {
        return target.containsKey("summary")
                || target.containsKey("cast")
                || target.containsKey("directors")
                || target.containsKey("rating")
                || target.containsKey("duration")
                || target.containsKey("releaseDate");
    }

    private int releaseYear(String value) {
        Matcher matcher = YEAR_PATTERN.matcher(value == null ? "" : value);
        int year = 0;
        while (matcher.find()) {
            year = Integer.parseInt(matcher.group(1));
        }
        return year;
    }

    private String firstTextValue(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private String normalizedTitle(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceFirst("^#\\s*\\d+\\s*", "")
                .replaceAll("(?<!\\d)(?:19|20)\\d{2}(?!\\d)", " ")
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
        return normalized.replaceFirst("^(the|a|an|le|la|les|un|une)\\s+", "");
    }

    private String normalizedUrl(String value) {
        return value == null ? "" : value.strip().replaceFirst("^https?://", "").replaceAll("/+$", "");
    }

    private boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private int intValue(Object value, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private record ProviderCoordinates(String baseUrl, String username, String password, String streamId) {
    }

    private record CacheEntry(Instant expiresAt, Optional<JsonNode> value) {
    }
}
