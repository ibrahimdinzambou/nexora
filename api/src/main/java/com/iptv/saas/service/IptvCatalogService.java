package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.repository.IptvAccountRepository;
import com.iptv.saas.web.ApiException;
import com.iptv.saas.web.Responses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class IptvCatalogService {
    public static final String ARCHIVED_BY_ADMIN = "archived-by-admin";
    private static final Logger LOGGER = LoggerFactory.getLogger(IptvCatalogService.class);
    private static final Pattern M3U_ITEM_ID = Pattern.compile("^m3u-(\\d+)-[a-f0-9]+$");
    private static final Pattern M3U_SERIES_ID = Pattern.compile("^m3u-series-(\\d+)-[a-f0-9]+$");
    private static final Pattern XTREAM_ITEM_ID = Pattern.compile(
            "^xtream-(\\d+)-(live|movie|series)-([a-zA-Z0-9_]+)$"
    );
    private static final Pattern XTREAM_SERIES_ID = Pattern.compile(
            "^xtream-series-(\\d+)-([a-zA-Z0-9]+)$"
    );
    private static final int MAX_BACKGROUND_CATALOG_REFRESHES = 2;

    private final IptvAccountRepository accounts;
    private final M3uPlaylistService playlists;
    private final XtreamCatalogService xtream;
    private final ProviderMetadataService metadata;
    private final CatalogImageService images;
    private final AtomicLong roundRobinSequence = new AtomicLong();
    private static final Map<String, String> LANGUAGE_NAMES = languageNames();

    public IptvCatalogService(
            IptvAccountRepository accounts,
            M3uPlaylistService playlists,
            XtreamCatalogService xtream,
            ProviderMetadataService metadata,
            CatalogImageService images
    ) {
        this.accounts = accounts;
        this.playlists = playlists;
        this.xtream = xtream;
        this.metadata = metadata;
        this.images = images;
    }

    public List<Map<String, Object>> categories(String type) {
        List<IptvAccount> catalogAccounts = activeCatalogAccounts();
        if (catalogAccounts.isEmpty()) {
            return demoCategories().stream()
                    .filter(category -> isType(category, type))
                    .toList();
        }

        Map<String, M3uPlaylistService.Category> unique = new LinkedHashMap<>();
        for (CatalogSource source : availableSources(catalogAccounts)) {
            for (M3uPlaylistService.Category category : source.playlist().categories()) {
                if (type == null || type.isBlank() || category.type().equals(type)) {
                    unique.putIfAbsent(categoryKey(category.type(), category.name()), category);
                }
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(M3uPlaylistService.Category::name, String.CASE_INSENSITIVE_ORDER))
                .map(this::categoryPayload)
                .toList();
    }

    public boolean hasActiveSources() {
        return !activeCatalogAccounts().isEmpty();
    }

    public List<Map<String, Object>> items(String type, String query, String categoryId) {
        return items(type, query, categoryId, "default", 0);
    }

    public List<Map<String, Object>> items(String type, String query, String categoryId, int requestedLimit) {
        return items(type, query, categoryId, "default", requestedLimit);
    }

    public List<Map<String, Object>> items(
            String type,
            String query,
            String categoryId,
            String sort,
            int requestedLimit
    ) {
        return items(type, query, categoryId, null, sort, requestedLimit);
    }

    public List<Map<String, Object>> items(
            String type,
            String query,
            String categoryId,
            String language,
            String sort,
            int requestedLimit
    ) {
        String normalizedType = type == null || type.isBlank() ? "live" : type;
        String normalizedQuery = normalizeIdentity(query);
        String normalizedSort = normalizedSort(sort);
        long limit = requestedLimit <= 0 ? Long.MAX_VALUE : requestedLimit;
        List<IptvAccount> catalogAccounts = activeCatalogAccounts();
        if (catalogAccounts.isEmpty()) {
            return demoItems().stream()
                    .filter(item -> item.get("type").equals(normalizedType))
                    .filter(item -> categoryId == null || categoryId.isBlank() || item.get("categoryId").equals(categoryId))
                    .filter(item -> matchesQuery(item.get("name"), normalizedQuery))
                    .sorted(demoItemComparator(sort))
                    .limit(limit)
                    .toList();
        }

        List<CatalogSource> sources = availableSources(catalogAccounts);
        if ("series".equals(normalizedType)) {
            return mergedSeries(sources).stream()
                    .filter(source -> categoryMatches(
                            "series",
                            source.series().categoryId(),
                            source.series().categoryName(),
                            categoryId
                    ))
                    .filter(source -> languageMatches(source.series().categoryName(), language))
                    .filter(source -> matchesSeriesQuery(source.series(), normalizedQuery))
                    .sorted(Comparator.comparing(
                            source -> source.series().title(),
                            String.CASE_INSENSITIVE_ORDER
                    ))
                    .limit(limit)
                    .map(source -> seriesPayload(source.series()))
                    .toList();
        }

        Comparator<EntrySource> sourceComparator = (left, right) -> itemComparator(sort).compare(left.entry(), right.entry());
        Map<String, EntrySource> unique = new LinkedHashMap<>();
        PriorityQueue<EntrySource> limited = requestedLimit > 0
                ? new PriorityQueue<>(sourceComparator.reversed())
                : null;
        boolean fastPreview = requestedLimit > 0
                && normalizedQuery.isBlank()
                && (categoryId == null || categoryId.isBlank())
                && (language == null || language.isBlank())
                && "default".equals(normalizedSort);
        for (CatalogSource source : sources) {
            for (M3uPlaylistService.Entry entry : source.playlist().entries()) {
                if (!entry.type().equals(normalizedType)
                        || !categoryMatches(entry.type(), entry.categoryId(), entry.categoryName(), categoryId)
                        || !languageMatches(entry.categoryName(), language)
                        || !matchesEntryQuery(entry, normalizedQuery)) {
                    continue;
                }
                String key = contentKey(entry);
                EntrySource candidate = new EntrySource(source.account(), entry);
                EntrySource existing = unique.get(key);
                if (existing != null) {
                    EntrySource preferred = preferredEntry(existing, candidate);
                    if (preferred != existing) {
                        unique.put(key, preferred);
                        if (limited != null) {
                            limited.remove(existing);
                            limited.offer(preferred);
                        }
                    }
                    continue;
                }
                if (requestedLimit <= 0 || unique.size() < requestedLimit) {
                    unique.put(key, candidate);
                    if (limited != null) {
                        limited.offer(candidate);
                    }
                    if (fastPreview && unique.size() >= requestedLimit) {
                        return unique.values().stream()
                                .map(entrySource -> entryPayload(entrySource.entry()))
                                .toList();
                    }
                    continue;
                }
                EntrySource worst = limited == null ? null : limited.peek();
                if (worst != null && sourceComparator.compare(candidate, worst) < 0) {
                    unique.remove(contentKey(worst.entry()));
                    limited.poll();
                    unique.put(key, candidate);
                    limited.offer(candidate);
                }
            }
        }
        return unique.values().stream()
                .sorted(sourceComparator)
                .limit(limit)
                .map(source -> entryPayload(source.entry()))
                .toList();
    }

    public List<Map<String, Object>> languages(String type) {
        List<IptvAccount> catalogAccounts = activeCatalogAccounts();
        if (catalogAccounts.isEmpty()) {
            return List.of();
        }
        Map<String, Set<String>> found = new LinkedHashMap<>();
        for (CatalogSource source : availableSources(catalogAccounts)) {
            for (M3uPlaylistService.Category category : source.playlist().categories()) {
                if (type != null && !type.isBlank() && !type.equals(category.type())) {
                    continue;
                }
                String language = languageCode(category.name());
                if (language != null) {
                    found.computeIfAbsent(language, ignored -> new TreeSet<>()).add(category.type());
                }
            }
        }
        return found.entrySet().stream()
                .sorted(Comparator.comparing(entry -> LANGUAGE_NAMES.get(entry.getKey())))
                .map(entry -> Map.<String, Object>of(
                        "id", entry.getKey(),
                        "name", LANGUAGE_NAMES.get(entry.getKey()),
                        "types", List.copyOf(entry.getValue())
                ))
                .toList();
    }

    private Comparator<M3uPlaylistService.Entry> itemComparator(String sort) {
        Comparator<M3uPlaylistService.Entry> byTitle = Comparator.comparing(
                item -> sortableTitle(item.name()),
                String.CASE_INSENSITIVE_ORDER
        );
        return switch (normalizedSort(sort)) {
            case "title-desc" -> byTitle.reversed();
            case "category" -> Comparator
                    .comparing(M3uPlaylistService.Entry::categoryName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(byTitle);
            case "recent" -> Comparator
                    .comparingInt((M3uPlaylistService.Entry item) -> M3uPlaylistService.releaseYear(item.name()))
                    .reversed()
                    .thenComparing(byTitle);
            case "title-asc" -> byTitle;
            default -> (left, right) -> 0;
        };
    }

    private Comparator<Map<String, Object>> demoItemComparator(String sort) {
        Comparator<Map<String, Object>> byTitle = Comparator.comparing(
                item -> sortableTitle(String.valueOf(item.get("name"))),
                String.CASE_INSENSITIVE_ORDER
        );
        return switch (normalizedSort(sort)) {
            case "title-desc" -> byTitle.reversed();
            case "category" -> Comparator.<Map<String, Object>, String>comparing(
                            item -> String.valueOf(item.get("categoryName")),
                            String.CASE_INSENSITIVE_ORDER
                    )
                    .thenComparing(byTitle);
            case "recent" -> Comparator
                    .comparingInt((Map<String, Object> item) ->
                            M3uPlaylistService.releaseYear(String.valueOf(item.get("name"))))
                    .reversed()
                    .thenComparing(byTitle);
            case "title-asc" -> byTitle;
            default -> (left, right) -> 0;
        };
    }

    private String normalizedSort(String sort) {
        return sort == null ? "default" : sort.strip().toLowerCase(Locale.ROOT);
    }

    static String sortableTitle(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
        return normalized.replaceFirst("^(le|la|les|un|une|des|the|a|an)\\s+", "");
    }

    public Map<String, Object> seriesInfo(String seriesId) {
        return seriesInfo(seriesId, null);
    }

    public Map<String, Object> seriesInfo(String seriesId, String titleHint) {
        Matcher xtreamMatcher = XTREAM_SERIES_ID.matcher(seriesId == null ? "" : seriesId);
        if (xtreamMatcher.matches()) {
            Long accountId = Long.parseLong(xtreamMatcher.group(1));
            IptvAccount account = requireCatalogAccount(accountId, Enums.IptvAccountType.XTREAM);
            M3uPlaylistService.Series summary = titleHint == null || titleHint.isBlank()
                    ? xtream.load(account).findSeries(seriesId)
                    : new M3uPlaylistService.Series(
                            seriesId,
                            titleHint,
                            "",
                            "Séries",
                            "",
                            List.of()
                    );
            return mergedSeriesDetails(
                    new SeriesSource(account, summary),
                    titleHint != null && !titleHint.isBlank()
            );
        }

        Matcher matcher = M3U_SERIES_ID.matcher(seriesId == null ? "" : seriesId);
        if (matcher.matches()) {
            Long accountId = Long.parseLong(matcher.group(1));
            IptvAccount account = requireM3uAccount(accountId);
            M3uPlaylistService.Series requested = playlists.load(account).findSeries(seriesId);
            return mergedSeriesDetails(new SeriesSource(account, requested), false);
        }

        Matcher episodeMatcher = M3U_ITEM_ID.matcher(seriesId == null ? "" : seriesId);
        if (episodeMatcher.matches()) {
            Long accountId = Long.parseLong(episodeMatcher.group(1));
            IptvAccount account = requireM3uAccount(accountId);
            M3uPlaylistService.Playlist playlist = playlists.load(account);
            M3uPlaylistService.Entry episode = playlist.find(seriesId);
            if ("series".equals(episode.type()) && episode.seriesId() != null) {
                return mergedSeriesDetails(
                        new SeriesSource(account, playlist.findSeries(episode.seriesId())),
                        false
                );
            }
        }

        Map<String, Object> body = Responses.map();
        body.put("id", seriesId);
        body.put("name", "Demo Series " + seriesId);
        body.put("type", "series");
        body.put("categoryId", "series-drama");
        body.put("categoryName", "Drama");
        body.put("poster", "");
        body.put("seasonCount", 1);
        body.put("episodeCount", 2);
        body.put("isSeries", true);
        body.put("seasons", List.of(
                Map.of("season", 1, "episodes", List.of(
                        Map.of("id", seriesId + "-s1e1", "name", "Épisode 1", "type", "series", "season", 1, "episode", 1, "isEpisode", true),
                        Map.of("id", seriesId + "-s1e2", "name", "Épisode 2", "type", "series", "season", 1, "episode", 2, "isEpisode", true)
                ))
        ));
        return body;
    }

    public Map<String, Object> itemInfo(String itemId) {
        Matcher xtreamMatcher = XTREAM_ITEM_ID.matcher(itemId == null ? "" : itemId);
        if (xtreamMatcher.matches()) {
            Long accountId = Long.parseLong(xtreamMatcher.group(1));
            IptvAccount account = requireCatalogAccount(accountId, Enums.IptvAccountType.XTREAM);
            M3uPlaylistService.Entry entry = xtream.load(account).find(itemId);
            if ("movie".equals(entry.type())) {
                Map<String, Object> details = new LinkedHashMap<>(metadata.movieDetails(account, entry));
                details.put("categoryId", canonicalCategoryId(entry.type(), entry.categoryName()));
                applyLanguage(details, entry.categoryName());
                images.rewrite(details);
                return details;
            }
            return entryPayload(entry);
        }

        Matcher matcher = M3U_ITEM_ID.matcher(itemId == null ? "" : itemId);
        if (matcher.matches()) {
            Long accountId = Long.parseLong(matcher.group(1));
            IptvAccount account = requireM3uAccount(accountId);
            M3uPlaylistService.Entry entry = playlists.load(account).find(itemId);
            if ("series".equals(entry.type()) && entry.seriesId() != null) {
                return mergedSeriesDetails(
                        new SeriesSource(account, playlists.load(account).findSeries(entry.seriesId())),
                        false
                );
            }
            if ("movie".equals(entry.type())) {
                Map<String, Object> details = new LinkedHashMap<>(metadata.movieDetails(account, entry));
                details.put("categoryId", canonicalCategoryId(entry.type(), entry.categoryName()));
                applyLanguage(details, entry.categoryName());
                images.rewrite(details);
                return details;
            }
            return entryPayload(entry);
        }

        return demoItems().stream()
                .filter(item -> String.valueOf(item.get("id")).equals(itemId))
                .findFirst()
                .map(LinkedHashMap::new)
                .map(item -> {
                    item.put("summary", "Une sélection de démonstration du catalogue Nexora.");
                    item.put("genres", List.of(String.valueOf(item.get("categoryName"))));
                    item.put("metadataAvailable", true);
                    return (Map<String, Object>) item;
                })
                .orElseThrow(() -> ApiException.notFound("Programme introuvable"));
    }

    public String categoryIdForItem(String itemId) {
        return accessForItem(itemId).categoryId();
    }

    public CatalogAccessDescriptor accessForItem(String itemId) {
        EntrySource requested = requestedEntry(itemId == null ? "" : itemId);
        if (requested != null) {
            return new CatalogAccessDescriptor(
                    canonicalCategoryId(requested.entry().type(), requested.entry().categoryName()),
                    requested.entry().categoryName(),
                    requested.entry().type(),
                    false
            );
        }
        return demoItems().stream()
                .filter(item -> String.valueOf(item.get("id")).equals(itemId))
                .map(item -> new CatalogAccessDescriptor(
                        stringValue(item.get("categoryId")),
                        stringValue(item.get("categoryName")),
                        stringValue(item.get("type")),
                        Boolean.TRUE.equals(item.get("adult"))
                ))
                .findFirst()
                .orElse(new CatalogAccessDescriptor(null, null, "all", false));
    }

    public List<Map<String, Object>> liveGroups() {
        return categories("live");
    }

    public List<Map<String, Object>> liveChannels() {
        return items("live", null, null);
    }

    @Transactional
    public IptvAccount saveAccount(Long id, String name, Enums.IptvAccountType type, String baseUrl, String username,
                                   String password, String playlistUrl, Integer maxStreams, Boolean active,
                                   Instant expiresAt) {
        IptvAccount account = id == null ? new IptvAccount() : accounts.findById(id)
                .orElseThrow(() -> ApiException.notFound("Compte IPTV introuvable"));
        if (name != null && !name.isBlank()) account.name = name;
        if (type != null) account.accountType = type;
        if (baseUrl != null) account.baseUrl = trimSlash(baseUrl);
        if (username != null) account.username = username;
        if (password != null) account.password = password;
        if (playlistUrl != null) account.playlistUrl = playlistUrl.strip();
        if (maxStreams != null) account.maxStreams = maxStreams;
        if (active != null) account.active = active;
        if (expiresAt != null) account.expiresAt = expiresAt;
        if (account.accountType == Enums.IptvAccountType.M3U
                && (account.playlistUrl == null || account.playlistUrl.isBlank())) {
            throw ApiException.validation("URL de playlist obligatoire pour un compte M3U");
        }
        account.lastHealthStatus = health(account);
        account = accounts.save(account);
        playlists.invalidate(account.id);
        xtream.invalidate(account.id);
        metadata.invalidate(account.id);
        return account;
    }

    @Transactional
    public void deleteAccount(Long id) {
        IptvAccount account = accounts.findById(id).orElseThrow(() -> ApiException.notFound("Compte IPTV introuvable"));
        account.active = false;
        account.disabled = true;
        account.activeStreams = 0;
        account.lastHealthStatus = "archived";
        account.disabledReason = ARCHIVED_BY_ADMIN;
        account.baseUrl = null;
        account.username = null;
        account.password = null;
        account.playlistUrl = null;
        accounts.save(account);
        playlists.invalidate(id);
        xtream.invalidate(id);
        metadata.invalidate(id);
    }

    @Transactional
    public Map<String, Object> syncLimits(Long id) {
        IptvAccount account = accounts.findById(id).orElseThrow(() -> ApiException.notFound("Compte IPTV introuvable"));
        boolean detected = false;
        if (account.accountType == Enums.IptvAccountType.XTREAM) {
            XtreamCatalogService.AccountLimits limits = xtream.accountLimits(account);
            if (limits.maxConnections() > 0) {
                account.maxStreams = limits.maxConnections();
                detected = true;
            } else if (account.maxStreams <= 0) {
                account.maxStreams = 1;
            }
            if (limits.activeConnections() >= 0) {
                account.activeStreams = limits.activeConnections();
            }
        }
        if (account.maxStreams <= 0) {
            account.maxStreams = 1;
        }
        account.lastHealthStatus = health(account);
        accounts.save(account);
        return Map.of(
                "maxStreams", account.maxStreams,
                "activeStreams", account.activeStreams,
                "health", account.lastHealthStatus,
                "detected", detected
        );
    }

    public Map<String, Object> refreshCache(Long id) {
        if (!accounts.existsById(id)) {
            throw ApiException.notFound("Compte IPTV introuvable");
        }
        playlists.invalidate(id);
        xtream.invalidate(id);
        metadata.invalidate(id);
        return Map.of("accountId", id, "cacheCleared", true);
    }

    public StreamSelection selectStream(String type, String itemId) {
        return selectStream(type, itemId, Set.of());
    }

    public StreamSelection selectStream(String type, String itemId, Long excludedAccountId) {
        return selectStream(
                type,
                itemId,
                excludedAccountId == null ? Set.of() : Set.of(excludedAccountId)
        );
    }

    public StreamSelection selectStream(String type, String itemId, Set<Long> excludedAccountIds) {
        Set<Long> excluded = excludedAccountIds == null ? Set.of() : excludedAccountIds;
        String normalizedItemId = itemId == null ? "" : itemId;
        if (M3U_SERIES_ID.matcher(normalizedItemId).matches()
                || XTREAM_SERIES_ID.matcher(normalizedItemId).matches()) {
            throw ApiException.validation("Sélectionnez un épisode avant de lancer la lecture");
        }
        EntrySource requestedSource = requestedEntry(normalizedItemId);
        if (requestedSource != null) {
            M3uPlaylistService.Entry requested = requestedSource.entry();
            String key = contentKey(requested);
            Map<Long, EntrySource> candidatesByAccount = new LinkedHashMap<>();
            if (!excluded.contains(requestedSource.account().id)
                    && (requestedSource.account().maxStreams <= 0
                    || requestedSource.account().activeStreams < requestedSource.account().maxStreams)) {
                candidatesByAccount.put(requestedSource.account().id, requestedSource);
                if (requestedSource.account().activeStreams == 0
                        && !hasRecentStreamFailure(requestedSource.account())) {
                    return new StreamSelection(
                            requestedSource.account(),
                            requestedSource.entry().streamUrl()
                    );
                }
            }
            List<IptvAccount> selectableAccounts = activeCatalogAccounts().stream()
                    .filter(account -> !excluded.contains(account.id))
                    .filter(account -> account.maxStreams <= 0 || account.activeStreams < account.maxStreams)
                    .toList();
            for (CatalogSource source : availableSources(selectableAccounts)) {
                equivalentEntries(source, requested).stream()
                        .findFirst()
                        .ifPresent(entry -> candidatesByAccount.put(source.account().id, entry));
            }
            if (candidatesByAccount.isEmpty()
                    && requestedSource.account().maxStreams > 0
                    && requestedSource.account().activeStreams >= requestedSource.account().maxStreams) {
                throw ApiException.serviceUnavailable(
                        "Ce programme est disponible sur une seule source, actuellement occupee. "
                                + "Le compte IPTV libre ne possede pas ce contenu."
                );
            }
            EntrySource selected = selectEntrySource(new ArrayList<>(candidatesByAccount.values()));
            return new StreamSelection(selected.account(), selected.entry().streamUrl());
        }
        IptvAccount account = bestAvailableAccount(excluded);
        return new StreamSelection(account, buildXtreamStreamUrl(account, type, itemId));
    }

    private EntrySource requestedEntry(String itemId) {
        Matcher m3uMatcher = M3U_ITEM_ID.matcher(itemId);
        if (m3uMatcher.matches()) {
            IptvAccount account = requireCatalogAccount(
                    Long.parseLong(m3uMatcher.group(1)),
                    Enums.IptvAccountType.M3U
            );
            return new EntrySource(account, playlists.load(account).find(itemId));
        }
        Matcher xtreamMatcher = XTREAM_ITEM_ID.matcher(itemId);
        if (xtreamMatcher.matches()) {
            IptvAccount account = requireCatalogAccount(
                    Long.parseLong(xtreamMatcher.group(1)),
                    Enums.IptvAccountType.XTREAM
            );
            if ("series".equals(xtreamMatcher.group(2))) {
                String compositeId = xtreamMatcher.group(3);
                int separator = compositeId.indexOf('_');
                if (separator < 1) {
                    throw ApiException.validation("Identifiant d'episode Xtream invalide");
                }
                String seriesPublicId = "xtream-series-" + account.id
                        + "-" + compositeId.substring(0, separator);
                M3uPlaylistService.Series series = xtream.loadSeries(account, seriesPublicId);
                return new EntrySource(account, series.episodes().stream()
                        .filter(entry -> entry.id().equals(itemId))
                        .findFirst()
                        .orElseThrow(() -> ApiException.notFound("Episode Xtream introuvable")));
            }
            return new EntrySource(account, xtream.load(account).find(itemId));
        }
        return null;
    }

    private IptvAccount requireM3uAccount(Long accountId) {
        return requireCatalogAccount(accountId, Enums.IptvAccountType.M3U);
    }

    private IptvAccount requireCatalogAccount(Long accountId, Enums.IptvAccountType type) {
        IptvAccount account = accounts.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("Compte IPTV introuvable"));
        if (!account.active || account.disabled || account.accountType != type) {
            throw ApiException.serviceUnavailable("Compte IPTV indisponible");
        }
        if (account.expiresAt != null && account.expiresAt.isBefore(Instant.now())) {
            throw ApiException.serviceUnavailable("Compte IPTV expire");
        }
        return account;
    }

    private String buildXtreamStreamUrl(IptvAccount account, String type, String itemId) {
        String base = trimSlash(account.baseUrl == null || account.baseUrl.isBlank() ? "https://example.com" : account.baseUrl);
        String user = account.username == null ? "user" : account.username;
        String pass = account.password == null ? "pass" : account.password;
        return switch (type == null ? "live" : type) {
            case "movie", "vod" -> base + "/movie/" + user + "/" + pass + "/" + itemId + ".mp4";
            case "series" -> base + "/series/" + user + "/" + pass + "/" + itemId + ".mp4";
            default -> base + "/live/" + user + "/" + pass + "/" + itemId + ".ts";
        };
    }

    public String health(IptvAccount account) {
        if (!account.active || account.disabled) {
            return "disabled";
        }
        if (account.expiresAt != null && account.expiresAt.isBefore(Instant.now())) {
            return "expired";
        }
        if (account.accountType == Enums.IptvAccountType.M3U
                && (account.playlistUrl == null || account.playlistUrl.isBlank())) {
            return "misconfigured";
        }
        if (account.accountType == Enums.IptvAccountType.XTREAM
                && (account.baseUrl == null || account.baseUrl.isBlank()
                || account.username == null || account.username.isBlank()
                || account.password == null || account.password.isBlank())) {
            return "misconfigured";
        }
        if (account.maxStreams > 0 && account.activeStreams >= account.maxStreams) {
            return "saturated";
        }
        return "ok";
    }

    public AccountAudit auditAccount(IptvAccount account) {
        String localHealth = health(account);
        if (List.of("disabled", "expired", "misconfigured").contains(localHealth)) {
            return new AccountAudit(account.id, account.name, localHealth, 0, 0, 0, false, localHealth);
        }

        try {
            M3uPlaylistService.Playlist playlist = account.accountType == Enums.IptvAccountType.M3U
                    ? playlists.load(account)
                    : xtream.load(account);
            int entries = playlist.entries().size();
            int series = playlist.series().size();
            int categories = playlist.categories().size();
            if (!hasDisplayableContent(playlist)) {
                return new AccountAudit(
                        account.id,
                        account.name,
                        "empty-catalog",
                        entries,
                        series,
                        categories,
                        false,
                        "Aucun programme exploitable dans ce catalogue"
                );
            }
            String status = "saturated".equals(localHealth) ? "saturated" : "ok";
            String message = "saturated".equals(status)
                    ? "Compte disponible mais limite de streams atteinte"
                    : "Catalogue disponible";
            return new AccountAudit(account.id, account.name, status, entries, series, categories, true, message);
        } catch (ApiException exception) {
            String status = exception.status().is4xxClientError() ? "misconfigured" : "catalog-unavailable";
            return new AccountAudit(
                    account.id,
                    account.name,
                    status,
                    0,
                    0,
                    0,
                    false,
                    exception.getMessage()
            );
        } catch (RuntimeException exception) {
            return new AccountAudit(
                    account.id,
                    account.name,
                    "catalog-unavailable",
                    0,
                    0,
                    0,
                    false,
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()
            );
        }
    }

    @Transactional(readOnly = true)
    public IptvAccount bestAvailableAccount() {
        return bestAvailableAccount(Set.of());
    }

    private IptvAccount bestAvailableAccount(Set<Long> excludedAccountIds) {
        Set<Long> excluded = excludedAccountIds == null ? Set.of() : excludedAccountIds;
        List<IptvAccount> candidates = accounts.findByActiveTrueAndDisabledFalse().stream()
                .filter(account -> !excluded.contains(account.id))
                .filter(account -> account.expiresAt == null || account.expiresAt.isAfter(Instant.now()))
                .filter(account -> account.maxStreams <= 0 || account.activeStreams < account.maxStreams)
                .toList();
        if (candidates.isEmpty()) {
            throw ApiException.serviceUnavailable("Aucun compte IPTV disponible");
        }
        List<IptvAccount> preferredCandidates = accountsWithoutRecentStreamFailures(candidates);
        double minimumLoad = preferredCandidates.stream().mapToDouble(this::loadRatio).min().orElse(0);
        List<IptvAccount> tied = preferredCandidates.stream()
                .filter(account -> Double.compare(loadRatio(account), minimumLoad) == 0)
                .sorted(Comparator.comparing(account -> account.id))
                .toList();
        return tied.get(roundRobinIndex(tied.size()));
    }

    private List<IptvAccount> activeCatalogAccounts() {
        return accounts.findByActiveTrueAndDisabledFalse().stream()
                .filter(account -> account.accountType == Enums.IptvAccountType.M3U
                        || account.accountType == Enums.IptvAccountType.XTREAM)
                .filter(account -> account.expiresAt == null || account.expiresAt.isAfter(Instant.now()))
                .filter(account -> catalogStatusVisible(account.lastHealthStatus))
                .toList();
    }

    private List<CatalogSource> availableSources(List<IptvAccount> catalogAccounts) {
        List<CatalogSource> sources = new ArrayList<>();
        List<IptvAccount> orderedAccounts = prioritizeCatalogAccounts(catalogAccounts);
        boolean hasReusableSource = orderedAccounts.stream().anyMatch(this::hasReusableCatalog);
        int backgroundRefreshes = 0;

        for (IptvAccount account : orderedAccounts) {
            boolean hasReusableCatalog = hasReusableCatalog(account);
            if (hasReusableSource && !hasReusableCatalog) {
                if (backgroundRefreshes < MAX_BACKGROUND_CATALOG_REFRESHES
                        && refreshCatalogInBackground(account)) {
                    backgroundRefreshes++;
                }
                LOGGER.debug(
                        "Chargement synchrone ignore pour le compte IPTV {} sans cache reutilisable",
                        account.id
                );
                continue;
            }
            try {
                M3uPlaylistService.Playlist playlist = account.accountType == Enums.IptvAccountType.M3U
                        ? playlists.load(account)
                        : xtream.load(account);
                if (!hasDisplayableContent(playlist)) {
                    LOGGER.warn(
                            "Catalogue IPTV vide ignore pour le compte {} ({})",
                            account.id,
                            account.name
                    );
                    continue;
                }
                sources.add(new CatalogSource(account, playlist));
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "Catalogue IPTV indisponible pour le compte {} ({}): {}",
                        account.id,
                        account.name,
                        exception.getMessage()
                );
            }
        }
        if (sources.isEmpty() && !catalogAccounts.isEmpty()) {
            throw ApiException.serviceUnavailable("Aucun catalogue IPTV n'est disponible");
        }
        return sources;
    }

    private List<IptvAccount> prioritizeCatalogAccounts(List<IptvAccount> catalogAccounts) {
        return catalogAccounts.stream()
                .sorted(Comparator
                        .comparing((IptvAccount account) -> !hasReusableCatalog(account))
                        .thenComparingDouble(this::loadRatio)
                        .thenComparing(account -> account.id))
                .toList();
    }

    private boolean hasReusableCatalog(IptvAccount account) {
        if (account.accountType == Enums.IptvAccountType.M3U) {
            return playlists.hasReusableCache(account.id);
        }
        if (account.accountType == Enums.IptvAccountType.XTREAM) {
            return xtream.hasReusableCache(account.id);
        }
        return false;
    }

    private boolean refreshCatalogInBackground(IptvAccount account) {
        try {
            if (account.accountType == Enums.IptvAccountType.M3U) {
                return playlists.refreshInBackground(account);
            }
            if (account.accountType == Enums.IptvAccountType.XTREAM) {
                return xtream.refreshInBackground(account);
            }
        } catch (RuntimeException exception) {
            LOGGER.debug(
                    "Refresh catalogue en arriere-plan ignore pour le compte {}: {}",
                    account.id,
                    exception.getMessage()
            );
        }
        return false;
    }

    private boolean hasDisplayableContent(M3uPlaylistService.Playlist playlist) {
        return playlist != null && (!playlist.entries().isEmpty() || !playlist.series().isEmpty());
    }

    private boolean catalogStatusVisible(String status) {
        String value = status == null ? "" : status.toLowerCase(Locale.ROOT);
        return !List.of(
                "disabled",
                "expired",
                "misconfigured",
                "empty-catalog",
                "catalog-unavailable",
                "archived"
        ).contains(value);
    }

    private List<SeriesSource> mergedSeries(List<CatalogSource> sources) {
        Map<String, List<SeriesSource>> grouped = new LinkedHashMap<>();
        for (CatalogSource source : sources) {
            for (M3uPlaylistService.Series series : source.playlist().series()) {
                grouped.computeIfAbsent(seriesKey(series), ignored -> new ArrayList<>())
                        .add(new SeriesSource(source.account(), series));
            }
        }
        return grouped.values().stream().map(this::mergeSeriesGroup).toList();
    }

    private SeriesSource mergeSeriesGroup(List<SeriesSource> group) {
        SeriesSource representative = group.stream()
                .min(Comparator
                        .comparing((SeriesSource source) -> source.series().poster() == null
                                || source.series().poster().isBlank())
                        .thenComparing(source -> source.account().id))
                .orElseThrow();

        Map<String, EntrySource> episodes = new LinkedHashMap<>();
        for (SeriesSource source : group) {
            for (M3uPlaylistService.Entry episode : source.series().episodes()) {
                episodes.merge(
                        contentKey(episode),
                        new EntrySource(source.account(), episode),
                        this::preferredEntry
                );
            }
        }
        List<M3uPlaylistService.Entry> mergedEpisodes = episodes.values().stream()
                .map(EntrySource::entry)
                .sorted(Comparator
                        .comparing(M3uPlaylistService.Entry::seasonNumber)
                        .thenComparing(M3uPlaylistService.Entry::episodeNumber)
                        .thenComparing(M3uPlaylistService.Entry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        M3uPlaylistService.Series merged = new M3uPlaylistService.Series(
                representative.series().id(),
                representative.series().title(),
                canonicalCategoryId("series", representative.series().categoryName()),
                representative.series().categoryName(),
                representative.series().poster(),
                mergedEpisodes
        );
        return new SeriesSource(representative.account(), merged);
    }

    private Map<String, Object> mergedSeriesDetails(
            SeriesSource requested,
            boolean preferAlternativeAccounts
    ) {
        String key = seriesKey(requested.series());
        Map<Long, SeriesSource> matching = new LinkedHashMap<>();
        if (!preferAlternativeAccounts) {
            matching.put(requested.account().id, requested);
        }
        List<IptvAccount> availableAccounts = activeCatalogAccounts().stream()
                .filter(account -> !preferAlternativeAccounts
                        || !Objects.equals(account.id, requested.account().id))
                .filter(account -> account.maxStreams <= 0 || account.activeStreams < account.maxStreams)
                .sorted(Comparator
                        .comparingDouble(this::loadRatio)
                        .thenComparing(account -> account.id))
                .toList();
        for (CatalogSource source : availableSources(availableAccounts)) {
            source.playlist().series().stream()
                    .filter(series -> seriesKey(series).equals(key))
                    .findFirst()
                    .ifPresent(series -> matching.put(source.account().id, new SeriesSource(source.account(), series)));
        }
        if (matching.isEmpty()) {
            matching.put(requested.account().id, requested);
        }

        List<SeriesSource> detailed = new ArrayList<>();
        List<SeriesSource> ordered = matching.values().stream()
                .sorted(Comparator
                        .comparing((SeriesSource source) ->
                                source.account().maxStreams > 0
                                        && source.account().activeStreams >= source.account().maxStreams)
                        .thenComparingDouble(source -> loadRatio(source.account()))
                        .thenComparing(source -> source.account().id))
                .toList();
        for (SeriesSource source : ordered) {
            boolean saturated = source.account().maxStreams > 0
                    && source.account().activeStreams >= source.account().maxStreams;
            if (saturated && !detailed.isEmpty()) {
                continue;
            }
            try {
                detailed.add(loadSeriesDetails(source));
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "Fiche serie indisponible pour le compte {} ({}): {}",
                        source.account().id,
                        source.account().name,
                        exception.getMessage()
                );
            }
        }
        if (detailed.isEmpty()) {
            throw ApiException.serviceUnavailable("Aucun compte IPTV ne peut charger cette série");
        }

        detailed.sort(Comparator
                .comparingDouble((SeriesSource source) -> loadRatio(source.account()))
                .thenComparing(source -> source.account().id));
        SeriesSource primary = detailed.get(0);
        SeriesSource merged = mergeSeriesGroup(detailed);
        Map<String, Object> details = new LinkedHashMap<>(primary.series().detailPayload());
        if (primary.account().accountType == Enums.IptvAccountType.M3U) {
            try {
                details.putAll(metadata.seriesDetails(primary.account(), primary.series()));
            } catch (RuntimeException exception) {
                LOGGER.warn("Metadonnees serie indisponibles pour le compte {}", primary.account().id);
            }
        }
        details.putAll(merged.series().detailPayload());
        applyLanguage(details, merged.series().categoryName());
        images.rewrite(details);
        return details;
    }

    private SeriesSource loadSeriesDetails(SeriesSource source) {
        if (source.account().accountType == Enums.IptvAccountType.XTREAM) {
            return new SeriesSource(
                    source.account(),
                    xtream.loadSeries(source.account(), source.series().id())
            );
        }
        return source;
    }

    private List<EntrySource> equivalentEntries(
            CatalogSource source,
            M3uPlaylistService.Entry requested
    ) {
        String key = contentKey(requested);
        List<EntrySource> matches = new ArrayList<>();
        source.playlist().entries().stream()
                .filter(entry -> contentKey(entry).equals(key))
                .findFirst()
                .ifPresent(entry -> matches.add(new EntrySource(source.account(), entry)));
        if (!"series".equals(requested.type()) || requested.seriesTitle() == null) {
            return matches;
        }

        source.playlist().series().stream()
                .filter(series -> normalizeSeriesIdentity(series.title())
                        .equals(normalizeSeriesIdentity(requested.seriesTitle())))
                .findFirst()
                .ifPresent(series -> {
                    try {
                        SeriesSource detailed = loadSeriesDetails(new SeriesSource(source.account(), series));
                        detailed.series().episodes().stream()
                                .filter(entry -> contentKey(entry).equals(key))
                                .findFirst()
                                .ifPresent(entry -> matches.add(new EntrySource(source.account(), entry)));
                    } catch (RuntimeException exception) {
                        LOGGER.warn(
                                "Episodes equivalents indisponibles pour le compte {}: {}",
                                source.account().id,
                                exception.getMessage()
                        );
                    }
                });
        return matches;
    }

    private EntrySource preferredEntry(EntrySource left, EntrySource right) {
        boolean leftFailed = hasRecentStreamFailure(left.account());
        boolean rightFailed = hasRecentStreamFailure(right.account());
        if (leftFailed != rightFailed) {
            return leftFailed ? right : left;
        }
        boolean leftAvailable = left.account().maxStreams <= 0
                || left.account().activeStreams < left.account().maxStreams;
        boolean rightAvailable = right.account().maxStreams <= 0
                || right.account().activeStreams < right.account().maxStreams;
        if (leftAvailable != rightAvailable) {
            return leftAvailable ? left : right;
        }
        int loadComparison = Double.compare(loadRatio(left.account()), loadRatio(right.account()));
        if (loadComparison != 0) {
            return loadComparison < 0 ? left : right;
        }
        boolean leftHasArtwork = left.entry().logo() != null && !left.entry().logo().isBlank();
        boolean rightHasArtwork = right.entry().logo() != null && !right.entry().logo().isBlank();
        if (leftHasArtwork != rightHasArtwork) {
            return leftHasArtwork ? left : right;
        }
        return left.account().id <= right.account().id ? left : right;
    }

    private EntrySource selectEntrySource(List<EntrySource> candidates) {
        if (candidates.isEmpty()) {
            throw ApiException.serviceUnavailable("Aucun compte IPTV ne peut servir ce contenu");
        }
        List<EntrySource> preferredCandidates = entriesWithoutRecentStreamFailures(candidates);
        double minimumLoad = preferredCandidates.stream()
                .map(EntrySource::account)
                .mapToDouble(this::loadRatio)
                .min()
                .orElse(0);
        List<EntrySource> tied = preferredCandidates.stream()
                .filter(source -> Double.compare(loadRatio(source.account()), minimumLoad) == 0)
                .sorted(Comparator.comparing(source -> source.account().id))
                .toList();
        return tied.get(roundRobinIndex(tied.size()));
    }

    private List<IptvAccount> accountsWithoutRecentStreamFailures(List<IptvAccount> candidates) {
        List<IptvAccount> available = candidates.stream()
                .filter(account -> !hasRecentStreamFailure(account))
                .toList();
        return available.isEmpty() ? candidates : available;
    }

    private List<EntrySource> entriesWithoutRecentStreamFailures(List<EntrySource> candidates) {
        List<EntrySource> available = candidates.stream()
                .filter(source -> !hasRecentStreamFailure(source.account()))
                .toList();
        return available.isEmpty() ? candidates : available;
    }

    private boolean hasRecentStreamFailure(IptvAccount account) {
        return account != null && "stream-failed".equalsIgnoreCase(account.lastHealthStatus);
    }

    private double loadRatio(IptvAccount account) {
        return account.maxStreams <= 0 ? 0 : (double) account.activeStreams / account.maxStreams;
    }

    private int roundRobinIndex(int size) {
        return (int) Math.floorMod(roundRobinSequence.getAndIncrement(), (long) size);
    }

    private Map<String, Object> categoryPayload(M3uPlaylistService.Category category) {
        Map<String, Object> payload = new LinkedHashMap<>(category.apiPayload());
        payload.put("id", canonicalCategoryId(category.type(), category.name()));
        return payload;
    }

    private Map<String, Object> entryPayload(M3uPlaylistService.Entry entry) {
        Map<String, Object> payload = new LinkedHashMap<>(entry.apiPayload());
        payload.put("categoryId", canonicalCategoryId(entry.type(), entry.categoryName()));
        applyLanguage(payload, entry.categoryName());
        images.rewrite(payload);
        return payload;
    }

    private Map<String, Object> seriesPayload(M3uPlaylistService.Series series) {
        Map<String, Object> payload = new LinkedHashMap<>(series.apiPayload());
        applyLanguage(payload, series.categoryName());
        images.rewrite(payload);
        return payload;
    }

    private void applyLanguage(Map<String, Object> payload, String categoryName) {
        String language = languageCode(categoryName);
        if (language != null) {
            payload.put("language", language);
            payload.put("languageName", LANGUAGE_NAMES.get(language));
        }
    }

    private boolean categoryMatches(String type, String sourceId, String name, String requestedId) {
        return requestedId == null
                || requestedId.isBlank()
                || requestedId.equals(sourceId)
                || requestedId.equals(canonicalCategoryId(type, name));
    }

    private String canonicalCategoryId(String type, String name) {
        return "catalog-cat-" + type + "-" + digest(categoryKey(type, name));
    }

    private String categoryKey(String type, String name) {
        return type + "|" + normalizeIdentity(name);
    }

    private String seriesKey(M3uPlaylistService.Series series) {
        return "series|" + normalizeSeriesIdentity(series.title());
    }

    private String contentKey(M3uPlaylistService.Entry entry) {
        if ("series".equals(entry.type())) {
            String title = entry.seriesTitle() == null ? entry.name() : entry.seriesTitle();
            return "series|" + normalizeSeriesIdentity(title)
                    + "|s" + entry.seasonNumber()
                    + "|e" + entry.episodeNumber();
        }
        return entry.type() + "|" + normalizeIdentity(entry.name());
    }

    private String normalizeSeriesIdentity(String value) {
        String normalized = normalizeIdentity(value);
        String previous;
        do {
            previous = normalized;
            normalized = normalized
                    .replaceFirst("\\s+(19|20)\\d{2}$", "")
                    .replaceFirst("\\s+(4k|uhd|fhd|hd|sd|multi|vostfr|vf|vff)$", "")
                    .strip();
        } while (!normalized.equals(previous));
        return normalized;
    }

    private String normalizeIdentity(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceFirst("^#\\s*\\d+\\s*", "")
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
    }

    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 12; index++) {
                hex.append(String.format("%02x", hash[index]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponible", exception);
        }
    }

    private boolean isType(Map<String, Object> item, String type) {
        return type == null || type.isBlank() || item.get("type").equals(type);
    }

    private boolean matchesQuery(Object value, String normalizedQuery) {
        return normalizedQuery == null || normalizedQuery.isBlank()
                || normalizeIdentity(String.valueOf(value)).contains(normalizedQuery);
    }

    private boolean matchesEntryQuery(M3uPlaylistService.Entry entry, String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return true;
        }
        String language = languageCode(entry.categoryName());
        return normalizedContains(entry.name(), normalizedQuery)
                || normalizedContains(entry.categoryName(), normalizedQuery)
                || normalizedContains(language, normalizedQuery)
                || normalizedContains(language == null ? null : LANGUAGE_NAMES.get(language), normalizedQuery);
    }

    private boolean matchesSeriesQuery(M3uPlaylistService.Series series, String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return true;
        }
        String language = languageCode(series.categoryName());
        return normalizedContains(series.title(), normalizedQuery)
                || normalizedContains(series.categoryName(), normalizedQuery)
                || normalizedContains(language, normalizedQuery)
                || normalizedContains(language == null ? null : LANGUAGE_NAMES.get(language), normalizedQuery)
                || (normalizedQuery.length() >= 3 && series.matchesNormalized(normalizedQuery));
    }

    private boolean normalizedContains(String value, String normalizedQuery) {
        return normalizeIdentity(value).contains(normalizedQuery);
    }

    private boolean languageMatches(String categoryName, String requestedLanguage) {
        return requestedLanguage == null
                || requestedLanguage.isBlank()
                || requestedLanguage.equalsIgnoreCase(languageCode(categoryName));
    }

    static String languageCode(String categoryName) {
        String normalized = normalizeLanguageText(categoryName);
        if (normalized.isBlank()) {
            return null;
        }

        String prefix = normalized.split("\\s+", 2)[0];
        if (normalized.contains("movies in english") || normalized.contains("english")) return "en";
        if (normalized.contains("vostfr") || normalized.matches(".*\\b(vf|vff|french|france)\\b.*")) return "fr";
        if (normalized.contains("kurd")) return "ku";
        if (normalized.contains("arab")) return "ar";
        if (normalized.contains("latino") || normalized.contains("latin america")) return "es";
        if (normalized.contains("brazil")) return "pt";
        if (normalized.contains("russian")) return "ru";
        if (normalized.contains("ukraine")) return "uk";
        if (normalized.contains("turk")) return "tr";
        if (normalized.contains("multi")) return "multi";

        return switch (prefix) {
            case "fr" -> "fr";
            case "en", "uk", "usa", "canada", "ca" -> "en";
            case "tr", "kktc" -> "tr";
            case "de", "at" -> "de";
            case "es", "lame" -> "es";
            case "pt", "br" -> "pt";
            case "it" -> "it";
            case "nl" -> "nl";
            case "pl" -> "pl";
            case "ar", "arab" -> "ar";
            case "ru" -> "ru";
            case "ukr", "ua" -> "uk";
            case "gr" -> "el";
            case "alb", "al" -> "sq";
            case "srb", "rs" -> "sr";
            case "cr", "cro", "hr" -> "hr";
            case "bih", "ba" -> "bs";
            case "mk" -> "mk";
            case "hu" -> "hu";
            case "slo", "si" -> "sl";
            case "cz" -> "cs";
            case "swe", "se" -> "sv";
            case "dk" -> "da";
            case "no" -> "no";
            case "fi" -> "fi";
            case "bg" -> "bg";
            case "ro" -> "ro";
            case "az" -> "az";
            case "kr" -> "ko";
            case "jp" -> "ja";
            case "ir" -> "fa";
            case "il" -> "he";
            case "in" -> "hi";
            case "pk" -> "ur";
            default -> null;
        };
    }

    private static String normalizeLanguageText(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
    }

    private static Map<String, String> languageNames() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("fr", "Français");
        names.put("en", "Anglais");
        names.put("tr", "Turc");
        names.put("de", "Allemand");
        names.put("es", "Espagnol");
        names.put("pt", "Portugais");
        names.put("it", "Italien");
        names.put("nl", "Néerlandais");
        names.put("pl", "Polonais");
        names.put("ar", "Arabe");
        names.put("ru", "Russe");
        names.put("uk", "Ukrainien");
        names.put("el", "Grec");
        names.put("sq", "Albanais");
        names.put("sr", "Serbe");
        names.put("hr", "Croate");
        names.put("bs", "Bosnien");
        names.put("mk", "Macédonien");
        names.put("hu", "Hongrois");
        names.put("sl", "Slovène");
        names.put("cs", "Tchèque");
        names.put("sv", "Suédois");
        names.put("da", "Danois");
        names.put("no", "Norvégien");
        names.put("fi", "Finnois");
        names.put("bg", "Bulgare");
        names.put("ro", "Roumain");
        names.put("az", "Azéri");
        names.put("ku", "Kurde");
        names.put("ko", "Coréen");
        names.put("ja", "Japonais");
        names.put("fa", "Persan");
        names.put("he", "Hébreu");
        names.put("hi", "Hindi");
        names.put("ur", "Ourdou");
        names.put("multi", "Multilingue");
        return Map.copyOf(names);
    }

    private List<Map<String, Object>> demoCategories() {
        return List.of(
                Map.of("id", "news", "name", "News", "type", "live"),
                Map.of("id", "sports", "name", "Sports", "type", "live"),
                Map.of("id", "movies-action", "name", "Action", "type", "movie"),
                Map.of("id", "series-drama", "name", "Drama", "type", "series")
        );
    }

    private List<Map<String, Object>> demoItems() {
        return List.of(
                Map.of("id", "1001", "name", "Africa News", "type", "live", "categoryId", "news", "logo", ""),
                Map.of("id", "1002", "name", "World Sports", "type", "live", "categoryId", "sports", "logo", ""),
                Map.of("id", "2001", "name", "Action Demo Movie", "type", "movie", "categoryId", "movies-action", "poster", ""),
                Map.of("id", "3001", "name", "Demo Series", "type", "series", "categoryId", "series-drama",
                        "poster", "", "seasonCount", 1, "episodeCount", 2, "isSeries", true)
        );
    }

    private String trimSlash(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("/+$", "");
    }

    private record CatalogSource(IptvAccount account, M3uPlaylistService.Playlist playlist) {
    }

    private record EntrySource(IptvAccount account, M3uPlaylistService.Entry entry) {
    }

    private record SeriesSource(IptvAccount account, M3uPlaylistService.Series series) {
    }

    public record StreamSelection(IptvAccount account, String streamUrl) {
    }

    public record AccountAudit(
            Long accountId,
            String accountName,
            String status,
            int entries,
            int series,
            int categories,
            boolean displayable,
            String message
    ) {
        public int contentCount() {
            return entries + series;
        }
    }

    public record CatalogAccessDescriptor(String categoryId, String categoryName, String contentType, boolean adult) {
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
