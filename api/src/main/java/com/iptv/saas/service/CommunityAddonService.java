package com.iptv.saas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iptv.saas.domain.CommunityAddon;
import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.CommunityAddonRepository;
import com.iptv.saas.repository.UserRepository;
import com.iptv.saas.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CommunityAddonService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommunityAddonService.class);
    private static final String ITEM_PREFIX = "addon~";
    private static final Set<String> SUPPORTED_TYPES = Set.of("live", "movie", "series");
    private static final Duration MAX_STALE_CACHE_AGE = Duration.ofHours(24);
    private static final int ADDON_PAGE_SIZE = 24;
    private static final int MAX_ADDON_PAGES = 10;

    private final CommunityAddonRepository addons;
    private final UserRepository users;
    private final CatalogImageService images;
    private final ObjectMapper mapper;
    private final TorBoxTorrentResolver torrentResolver;
    private final HttpClient httpClient;
    private final int maxResponseBytes;
    private final Duration cacheTtl;
    private final boolean allowPrivateHosts;
    private final Map<String, CachedJson> responseCache = new ConcurrentHashMap<>();

    public CommunityAddonService(
            CommunityAddonRepository addons,
            UserRepository users,
            CatalogImageService images,
            ObjectMapper mapper,
            TorBoxTorrentResolver torrentResolver,
            @Value("${app.addons.max-response-bytes:2097152}") int maxResponseBytes,
            @Value("${app.addons.cache-ttl-seconds:300}") long cacheTtlSeconds,
            @Value("${app.addons.allow-private-hosts:false}") boolean allowPrivateHosts
    ) {
        this.addons = addons;
        this.users = users;
        this.images = images;
        this.mapper = mapper;
        this.torrentResolver = torrentResolver;
        this.maxResponseBytes = Math.max(65_536, maxResponseBytes);
        this.cacheTtl = Duration.ofSeconds(Math.max(30, cacheTtlSeconds));
        this.allowPrivateHosts = allowPrivateHosts;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UserEntity actor) {
        return addons.findAllByOrderByCreatedAtDesc().stream()
                .filter(addon -> !addon.privateUse
                        || sameUser(addon.owner, actor)
                        || isSuperAdmin(actor))
                .map(this::payload)
                .toList();
    }

    @Transactional
    public CommunityAddon install(
            String manifestUrl,
            String allowedStreamHosts,
            String licenseName,
            String licenseUrl,
            boolean adultContent,
            boolean privateUse,
            UserEntity actor
    ) {
        URI uri = safeUri(manifestUrl, true);
        if (addons.findByManifestUrl(uri.toString()).isPresent()) {
            throw ApiException.validation("Cet add-on est deja installe");
        }
        JsonNode manifest = fetchJson(uri, false);
        validateManifest(manifest);

        CommunityAddon addon = new CommunityAddon();
        addon.manifestUrl = uri.toString();
        applyManifest(addon, manifest);
        addon.allowedStreamHosts = normalizedHosts(
                allowedStreamHosts == null || allowedStreamHosts.isBlank() ? uri.getHost() : allowedStreamHosts
        );
        addon.licenseName = clean(licenseName);
        addon.licenseUrl = clean(licenseUrl);
        addon.adultContent = adultContent;
        addon.privateUse = privateUse;
        addon.owner = privateUse ? requireActor(actor) : null;
        addon.status = Enums.AddonStatus.PENDING;
        return addons.save(addon);
    }

    public CommunityAddon install(
            String manifestUrl,
            String allowedStreamHosts,
            String licenseName,
            String licenseUrl,
            boolean adultContent
    ) {
        return install(manifestUrl, allowedStreamHosts, licenseName, licenseUrl, adultContent, false, null);
    }

    @Transactional
    public CommunityAddon update(
            Long id,
            String manifestUrl,
            String allowedStreamHosts,
            String licenseName,
            String licenseUrl,
            boolean adultContent,
            boolean privateUse,
            UserEntity actor
    ) {
        CommunityAddon addon = requireManaged(id, actor);
        URI manifestUri = safeUri(manifestUrl, true);
        if (!manifestUri.toString().equals(addon.manifestUrl)) {
            addons.findByManifestUrl(manifestUri.toString())
                    .filter(existing -> !existing.id.equals(addon.id))
                    .ifPresent(existing -> {
                        throw ApiException.validation("Cet add-on est deja installe");
                    });
            JsonNode manifest = fetchJson(manifestUri, false);
            validateManifest(manifest);
            invalidate(addon);
            addon.manifestUrl = manifestUri.toString();
            applyManifest(addon, manifest);
        }
        addon.allowedStreamHosts = normalizedHosts(allowedStreamHosts);
        addon.licenseName = clean(licenseName);
        addon.licenseUrl = clean(licenseUrl);
        addon.adultContent = adultContent;
        boolean wasPrivate = addon.privateUse;
        addon.privateUse = privateUse;
        if (privateUse) {
            if (!wasPrivate || addon.owner == null) {
                addon.owner = requireActor(actor);
            }
        } else {
            addon.owner = null;
            addon.allowedUsers.clear();
        }
        addon.status = Enums.AddonStatus.PENDING;
        invalidate(addon);
        return addons.save(addon);
    }

    @Transactional
    public CommunityAddon approve(Long id, UserEntity actor) {
        CommunityAddon addon = requireManaged(id, actor);
        if (!addon.privateUse && (addon.licenseName == null || addon.licenseUrl == null)) {
            throw ApiException.validation("La licence et son URL sont requises avant publication");
        }
        if (!addon.privateUse) {
            safeUri(addon.licenseUrl, false);
        }
        if (parseHosts(addon.allowedStreamHosts).isEmpty()) {
            throw ApiException.validation("Ajoutez au moins un domaine de diffusion autorise");
        }
        addon.status = Enums.AddonStatus.APPROVED;
        addon.lastError = null;
        return addons.save(addon);
    }

    @Transactional
    public CommunityAddon updateAccess(Long id, List<Long> userIds, UserEntity actor) {
        if (actor == null || actor.role != Enums.UserRole.SUPER_ADMIN) {
            throw ApiException.forbidden("Seul le super administrateur peut partager un add-on prive");
        }
        CommunityAddon addon = require(id);
        if (!addon.privateUse) {
            throw ApiException.validation("La liste restreinte concerne uniquement les add-ons prives");
        }
        Set<Long> requested = new LinkedHashSet<>(userIds == null ? List.of() : userIds);
        requested.remove(null);
        if (addon.owner != null) {
            requested.remove(addon.owner.id);
        }
        List<UserEntity> selected = users.findAllById(requested).stream()
                .filter(user -> user.active)
                .toList();
        if (selected.size() != requested.size()) {
            throw ApiException.validation("Un ou plusieurs utilisateurs sont introuvables ou inactifs");
        }
        addon.allowedUsers.clear();
        addon.allowedUsers.addAll(selected);
        return addons.save(addon);
    }

    @Transactional
    public CommunityAddon disable(Long id, UserEntity actor) {
        CommunityAddon addon = requireManaged(id, actor);
        addon.status = Enums.AddonStatus.DISABLED;
        invalidate(addon);
        return addons.save(addon);
    }

    @Transactional
    public CommunityAddon refresh(Long id, UserEntity actor) {
        CommunityAddon addon = requireManaged(id, actor);
        try {
            JsonNode manifest = fetchJson(safeUri(addon.manifestUrl, true), false);
            validateManifest(manifest);
            applyManifest(addon, manifest);
            addon.status = Enums.AddonStatus.PENDING;
            addon.lastError = null;
            invalidate(addon);
        } catch (ApiException exception) {
            addon.lastCheckedAt = Instant.now();
            addon.lastError = exception.getMessage();
            addons.save(addon);
            throw exception;
        }
        return addons.save(addon);
    }

    @Transactional
    public void delete(Long id, UserEntity actor) {
        CommunityAddon addon = requireManaged(id, actor);
        invalidate(addon);
        addons.delete(addon);
    }

    @Transactional(readOnly = true)
    public boolean hasApprovedAddons(UserEntity user) {
        return approved(user).stream().anyMatch(this::hasCatalogs);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> categories(String type, UserEntity user) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CommunityAddon addon : approved(user)) {
            List<CatalogDefinition> addonCatalogs;
            try {
                addonCatalogs = catalogs(addon);
            } catch (ApiException exception) {
                logAddonUnavailable(addon, exception);
                continue;
            }
            for (CatalogDefinition catalog : addonCatalogs) {
                if (type == null || type.isBlank() || type.equals(catalog.type())) {
                    Map<String, Object> category = new LinkedHashMap<>();
                    category.put("id", categoryId(addon.id, catalog.type(), catalog.id()));
                    category.put("name", catalog.name());
                    category.put("type", catalog.type());
                    category.put("source", addon.name);
                    category.put("addonKey", addon.addonKey);
                    category.put("filterName", catalog.filterName());
                    category.put("filterOptions", catalog.filterOptions());
                    category.put("filterRequired", catalog.filterRequired());
                    category.put("searchOnly", catalog.searchOnly());
                    category.put("adult", addon.adultContent);
                    category.put("privateUse", addon.privateUse);
                    category.put("privateAccess", addon.privateUse);
                    category.put("ownerId", addon.owner == null ? null : addon.owner.id);
                    category.put("metadataAvailable", catalog.metadataAvailable());
                    category.put("streamAvailable", catalog.streamAvailable());
                    result.add(category);
                }
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> items(
            String type,
            String query,
            String categoryId,
            String sort,
            int requestedLimit,
            UserEntity user
    ) {
        return items(type, query, categoryId, sort, requestedLimit, null, 1, user);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> items(
            String type,
            String query,
            String categoryId,
            String sort,
            int requestedLimit,
            String addonFilter,
            int requestedPages,
            UserEntity user
    ) {
        String normalizedType = normalizeType(type);
        boolean searching = query != null && !query.isBlank();
        int pageCount = Math.max(1, Math.min(MAX_ADDON_PAGES, requestedPages));
        List<Map<String, Object>> result = new ArrayList<>();
        for (CommunityAddon addon : approved(user)) {
            try {
                for (CatalogDefinition catalog : catalogs(addon)) {
                    if (!normalizedType.equals(catalog.type())) {
                        continue;
                    }
                    String addonCategoryId = categoryId(addon.id, catalog.type(), catalog.id());
                    if (categoryId != null && !categoryId.isBlank() && !categoryId.equals(addonCategoryId)) {
                        continue;
                    }
                    if (searching && !catalog.searchOnly()) {
                        continue;
                    }
                    if (!searching && catalog.searchOnly()) {
                        continue;
                    }

                    Map<String, String> baseExtras = new LinkedHashMap<>();
                    if (catalog.searchOnly()) {
                        baseExtras.put(catalog.filterName(), query.strip());
                    } else if (catalog.filterName() != null) {
                        String selected = clean(addonFilter);
                        if (selected == null || categoryId == null || categoryId.isBlank()) {
                            selected = catalog.filterOptions().isEmpty() ? null : catalog.filterOptions().get(0);
                        }
                        if (selected != null) {
                            baseExtras.put(catalog.filterName(), selected);
                        } else if (catalog.filterRequired()) {
                            continue;
                        }
                    }

                    int pages = categoryId == null || categoryId.isBlank() ? 1 : pageCount;
                    if (searching) {
                        pages = pageCount;
                    }
                    for (int page = 0; page < pages; page++) {
                        Map<String, String> extras = new LinkedHashMap<>(baseExtras);
                        if (page > 0 && catalog.supportsSkip()) {
                            extras.put("skip", String.valueOf(page * ADDON_PAGE_SIZE));
                        }
                        JsonNode response = fetchJson(
                                endpoint(addon, "catalog", catalog.remoteType(), catalog.id(), extras),
                                true
                        );
                        JsonNode values = response.has("metas") ? response.path("metas") : response.path("items");
                        if (!values.isArray() || values.isEmpty()) {
                            break;
                        }
                        for (JsonNode value : values) {
                            try {
                                if (!catalog.type().equals(itemType(value, catalog))) {
                                    continue;
                                }
                                Map<String, Object> item = itemPayload(addon, catalog, value);
                                if (matchesQuery(item, searching && !catalog.searchOnly() ? query : null)) {
                                    result.add(item);
                                }
                            } catch (ApiException ignored) {
                                // One malformed community entry must not hide the rest of the catalog.
                            }
                        }
                        if (!catalog.supportsSkip() || values.size() < ADDON_PAGE_SIZE) {
                            break;
                        }
                    }
                }
            } catch (ApiException exception) {
                logAddonUnavailable(addon, exception);
            }
        }
        Comparator<Map<String, Object>> byName = Comparator.comparing(
                item -> String.valueOf(item.get("name")),
                String.CASE_INSENSITIVE_ORDER
        );
        if ("title-desc".equals(sort)) {
            byName = byName.reversed();
        }
        result.sort(byName);
        int limit = requestedLimit <= 0 ? result.size() : Math.min(requestedLimit, result.size());
        return List.copyOf(result.subList(0, limit));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> itemInfo(String publicItemId, UserEntity user) {
        AddonItemId itemId = parseItemId(publicItemId);
        CommunityAddon addon = requireApproved(itemId.addonId(), user);
        CatalogDefinition catalog = requireCatalog(addon, itemId.type(), itemId.catalogId());
        JsonNode meta = metadata(addon, catalog, itemId);
        Map<String, Object> payload = itemPayload(addon, catalog, meta);
        payload.put("id", publicItemId);
        payload.put("summary", text(meta, "description", "overview"));
        payload.put("duration", text(meta, "runtime", "duration"));
        payload.put("rating", text(meta, "imdbRating", "rating"));
        payload.put("releaseYear", text(meta, "releaseInfo", "year"));
        payload.put("genres", stringList(meta.path("genres")));
        payload.put("cast", stringList(meta.path("cast")));
        payload.put("directors", stringList(meta.path("director")));
        payload.put("country", text(meta, "country"));
        applyStreamAvailability(addon, catalog, itemId, payload, user);
        images.rewrite(payload);
        return payload;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> seriesInfo(String publicItemId, UserEntity user) {
        AddonItemId itemId = parseItemId(publicItemId);
        if (!"series".equals(itemId.type())) {
            throw ApiException.validation("Cet element n'est pas une serie");
        }
        CommunityAddon addon = requireApproved(itemId.addonId(), user);
        CatalogDefinition catalog = requireCatalog(addon, itemId.type(), itemId.catalogId());
        JsonNode meta = metadata(addon, catalog, itemId);
        Map<String, Object> payload = itemPayload(addon, catalog, meta);
        payload.put("id", publicItemId);
        payload.put("summary", text(meta, "description", "overview"));
        payload.put("rating", text(meta, "imdbRating", "rating"));
        payload.put("releaseYear", text(meta, "releaseInfo", "year"));
        payload.put("genres", stringList(meta.path("genres")));
        payload.put("cast", stringList(meta.path("cast")));
        payload.put("directors", stringList(meta.path("director")));

        Map<Integer, List<Map<String, Object>>> episodesBySeason = new LinkedHashMap<>();
        JsonNode videos = meta.path("videos");
        if (videos.isArray()) {
            for (JsonNode video : videos) {
                String remoteEpisodeId = text(video, "id");
                if (remoteEpisodeId == null) {
                    continue;
                }
                int season = Math.max(1, video.path("season").asInt(1));
                int episode = Math.max(1, video.path("episode").asInt(
                        episodesBySeason.getOrDefault(season, List.of()).size() + 1
                ));
                Map<String, Object> episodePayload = new LinkedHashMap<>();
                episodePayload.put("id", publicItemId(addon.id, "series", catalog.id(), remoteEpisodeId));
                episodePayload.put("name", firstText(video, "title", "name", "Episode " + episode));
                episodePayload.put("episode", episode);
                episodePayload.put("season", season);
                episodePayload.put("summary", text(video, "overview", "description"));
                episodePayload.put("poster", text(video, "thumbnail", "poster"));
                episodePayload.put("releaseDate", text(video, "released"));
                episodePayload.put("duration", text(video, "runtime"));
                episodePayload.put("streamAvailable", catalog.streamAvailable());
                episodesBySeason.computeIfAbsent(season, ignored -> new ArrayList<>()).add(episodePayload);
            }
        }
        List<Map<String, Object>> seasons = episodesBySeason.entrySet().stream().map(entry -> {
            Map<String, Object> season = new LinkedHashMap<>();
            season.put("season", entry.getKey());
            season.put("name", "Saison " + entry.getKey());
            season.put("episodeCount", entry.getValue().size());
            season.put("episodes", entry.getValue());
            return season;
        }).toList();
        payload.put("seasons", seasons);
        payload.put("seasonCount", seasons.size());
        payload.put("episodeCount", seasons.stream()
                .mapToInt(season -> ((List<?>) season.get("episodes")).size())
                .sum());
        images.rewrite(payload);
        return payload;
    }

    @Transactional(readOnly = true)
    public String categoryIdForItem(String publicItemId, UserEntity user) {
        AddonItemId itemId = parseItemId(publicItemId);
        CommunityAddon addon = requireApproved(itemId.addonId(), user);
        requireCatalog(addon, itemId.type(), itemId.catalogId());
        return categoryId(addon.id, itemId.type(), itemId.catalogId());
    }

    @Transactional(readOnly = true)
    public boolean isAdultItem(String publicItemId, UserEntity user) {
        return requireApproved(parseItemId(publicItemId).addonId(), user).adultContent;
    }

    @Transactional(readOnly = true)
    public boolean hasPrivateAccess(String publicItemId, UserEntity user) {
        CommunityAddon addon = requireApproved(parseItemId(publicItemId).addonId(), user);
        return addon.privateUse && canAccess(addon, user);
    }

    @Transactional(readOnly = true)
    public String streamUrl(String publicItemId, UserEntity user) {
        AddonItemId itemId = parseItemId(publicItemId);
        CommunityAddon addon = requireApproved(itemId.addonId(), user);
        CatalogDefinition catalog = requireCatalog(addon, itemId.type(), itemId.catalogId());
        StreamResolution resolution = streamResolutionWithFallback(addon, catalog, itemId, user, true);
        if (resolution.available()) {
            return resolution.url();
        }
        throw ApiException.serviceUnavailable(resolution.reason());
    }

    private void applyStreamAvailability(
            CommunityAddon addon,
            CatalogDefinition catalog,
            AddonItemId itemId,
            Map<String, Object> payload,
            UserEntity user
    ) {
        StreamResolution resolution;
        try {
            resolution = streamResolutionWithFallback(addon, catalog, itemId, user, false);
        } catch (ApiException exception) {
            resolution = StreamResolution.unavailable(exception.getMessage());
        }
        payload.put("streamAvailable", resolution.available());
        if (!resolution.available()) {
            payload.put("streamUnavailableReason", resolution.reason());
        }
    }

    private StreamResolution streamResolution(
            CommunityAddon addon,
            CatalogDefinition catalog,
            AddonItemId itemId,
            boolean resolveTorrent
    ) {
        if (!catalog.streamAvailable()) {
            return StreamResolution.unavailable("Cet add-on fournit un catalogue sans flux de lecture");
        }
        String resourceType = resourceType(addon, catalog, "stream", itemId.type());
        return streamResolution(addon, resourceType, itemId.remoteId(), resolveTorrent);
    }

    private StreamResolution streamResolutionWithFallback(
            CommunityAddon addon,
            CatalogDefinition catalog,
            AddonItemId itemId,
            UserEntity user,
            boolean resolveTorrent
    ) {
        StreamResolution primary = streamResolution(addon, catalog, itemId, resolveTorrent);
        if (primary.available()) {
            return primary;
        }
        StreamResolution fallback = streamOnlyResolution(itemId, user, addon.id, resolveTorrent);
        return fallback.available() ? fallback : primary;
    }

    private StreamResolution streamOnlyResolution(
            AddonItemId itemId,
            UserEntity user,
            Long excludedAddonId,
            boolean resolveTorrent
    ) {
        StreamResolution lastFailure = null;
        for (CommunityAddon resolver : approved(user)) {
            if (resolver.id != null && resolver.id.equals(excludedAddonId)) {
                continue;
            }
            if (!streamResolverSupports(resolver, itemId.type())) {
                continue;
            }
            for (String remoteId : streamRemoteIds(itemId.remoteId())) {
                if (!matchesIdPrefixes(resolver, remoteId)) {
                    continue;
                }
                try {
                    StreamResolution resolution = streamResolution(
                            resolver,
                            streamResourceType(resolver, itemId.type()),
                            remoteId,
                            resolveTorrent
                    );
                    if (resolution.available()) {
                        return resolution;
                    }
                    lastFailure = resolution;
                } catch (ApiException exception) {
                    lastFailure = StreamResolution.unavailable(exception.getMessage());
                }
            }
        }
        return lastFailure == null
                ? StreamResolution.unavailable("Aucun add-on de lecture compatible disponible")
                : lastFailure;
    }

    private StreamResolution streamResolution(
            CommunityAddon addon,
            String resourceType,
            String remoteId,
            boolean resolveTorrent
    ) {
        JsonNode response = fetchJson(endpoint(addon, "stream", resourceType, remoteId), false);
        JsonNode streams = response.path("streams");
        if (!streams.isArray()) {
            return StreamResolution.unavailable("L'add-on n'a retourne aucun flux compatible");
        }
        boolean torrentFound = false;
        String notWebReadyFallback = null;
        Set<String> rejectedHosts = new LinkedHashSet<>();
        for (JsonNode stream : streams) {
            String url = text(stream, "url");
            if (url != null) {
                URI uri = safeUri(url, false);
                if (hostAllowed(uri.getHost(), parseHosts(addon.allowedStreamHosts))) {
                    if (!stream.path("behaviorHints").path("notWebReady").asBoolean(false)) {
                        return StreamResolution.available(uri.toString());
                    }
                    if (notWebReadyFallback == null) {
                        notWebReadyFallback = uri.toString();
                    }
                } else if (uri.getHost() != null) {
                    rejectedHosts.add(uri.getHost().toLowerCase(Locale.ROOT));
                }
            }
            if (torrentResolver.supports(stream)) {
                torrentFound = true;
                if (torrentResolver.configured()) {
                    return resolveTorrent
                            ? StreamResolution.available(torrentResolver.resolve(stream))
                            : StreamResolution.available(null);
                }
            }
        }
        if (notWebReadyFallback != null) {
            return StreamResolution.available(notWebReadyFallback);
        }
        if (torrentFound) {
            return StreamResolution.unavailable("Cet add-on fournit un torrent; configurez TORBOX_API_TOKEN pour le lire");
        }
        if (!rejectedHosts.isEmpty()) {
            return StreamResolution.unavailable(
                    "Aucun flux ne correspond aux domaines autorises: "
                            + String.join(", ", rejectedHosts)
            );
        }
        return StreamResolution.unavailable("Aucun flux ne correspond aux domaines autorises");
    }

    public boolean isAddonItem(String itemId) {
        return itemId != null && itemId.startsWith(ITEM_PREFIX);
    }

    @Transactional(readOnly = true)
    public boolean isAllowedStreamHostForPlayback(String publicItemId, UserEntity user, String host) {
        if (!isAddonItem(publicItemId)) {
            return false;
        }
        AddonItemId itemId = parseItemId(publicItemId);
        CommunityAddon addon = requireApproved(itemId.addonId(), user);
        if (hostAllowed(host, parseHosts(addon.allowedStreamHosts))) {
            return true;
        }
        return approved(user).stream()
                .filter(resolver -> resolver.id == null || !resolver.id.equals(addon.id))
                .filter(resolver -> streamResolverSupports(resolver, itemId.type()))
                .anyMatch(resolver -> hostAllowed(host, parseHosts(resolver.allowedStreamHosts)));
    }

    public List<Map<String, Object>> categories(String type) {
        return categories(type, null);
    }

    public List<Map<String, Object>> items(
            String type,
            String query,
            String categoryId,
            String sort,
            int requestedLimit
    ) {
        return items(type, query, categoryId, sort, requestedLimit, null);
    }

    public Map<String, Object> itemInfo(String publicItemId) {
        return itemInfo(publicItemId, null);
    }

    public Map<String, Object> seriesInfo(String publicItemId) {
        return seriesInfo(publicItemId, null);
    }

    public String streamUrl(String publicItemId) {
        return streamUrl(publicItemId, null);
    }

    private List<CommunityAddon> approved(UserEntity user) {
        return addons.findByStatusOrderByNameAsc(Enums.AddonStatus.APPROVED).stream()
                .filter(addon -> canAccess(addon, user))
                .toList();
    }

    private CommunityAddon require(Long id) {
        return addons.findById(id).orElseThrow(() -> ApiException.notFound("Add-on introuvable"));
    }

    private CommunityAddon requireApproved(Long id, UserEntity user) {
        CommunityAddon addon = require(id);
        if (addon.status != Enums.AddonStatus.APPROVED || !canAccess(addon, user)) {
            throw ApiException.forbidden("Cet add-on n'est pas publie");
        }
        return addon;
    }

    private CommunityAddon requireManaged(Long id, UserEntity actor) {
        CommunityAddon addon = require(id);
        if (addon.privateUse && !sameUser(addon.owner, actor) && !isSuperAdmin(actor)) {
            throw ApiException.forbidden("Cet add-on prive appartient a un autre utilisateur");
        }
        return addon;
    }

    private UserEntity requireActor(UserEntity actor) {
        if (actor == null || actor.id == null) {
            throw ApiException.unauthorized("Un proprietaire authentifie est requis");
        }
        return actor;
    }

    private boolean canAccess(CommunityAddon addon, UserEntity user) {
        return !addon.privateUse
                || sameUser(addon.owner, user)
                || addon.allowedUsers.stream().anyMatch(allowed -> sameUser(allowed, user));
    }

    private boolean sameUser(UserEntity first, UserEntity second) {
        return first != null && second != null && first.id != null && first.id.equals(second.id);
    }

    private boolean isSuperAdmin(UserEntity user) {
        return user != null && user.role == Enums.UserRole.SUPER_ADMIN;
    }

    private Map<String, Object> payload(CommunityAddon addon) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", addon.id);
        result.put("addonKey", addon.addonKey);
        result.put("name", addon.name);
        result.put("version", addon.version);
        result.put("description", addon.description);
        result.put("manifestUrl", addon.manifestUrl);
        result.put("status", addon.status);
        result.put("allowedStreamHosts", addon.allowedStreamHosts);
        result.put("licenseName", addon.licenseName);
        result.put("licenseUrl", addon.licenseUrl);
        result.put("adultContent", addon.adultContent);
        result.put("privateUse", addon.privateUse);
        result.put("ownerId", addon.owner == null ? null : addon.owner.id);
        result.put("ownerEmail", addon.owner == null ? null : addon.owner.email);
        result.put("allowedUserIds", addon.allowedUsers.stream()
                .map(user -> user.id)
                .sorted()
                .toList());
        result.put("allowedUsers", addon.allowedUsers.stream()
                .sorted(Comparator.comparing(user -> user.email, String.CASE_INSENSITIVE_ORDER))
                .map(user -> Map.of(
                        "id", user.id,
                        "name", user.name,
                        "email", user.email,
                        "role", user.role
                ))
                .toList());
        result.put("torrentResolver", torrentResolver.status());
        result.put("catalogs", catalogs(addon).stream().map(catalog -> Map.of(
                "id", catalog.id(),
                "name", catalog.name(),
                "type", catalog.type(),
                "remoteType", catalog.remoteType(),
                "metadataAvailable", catalog.metadataAvailable(),
                "streamAvailable", catalog.streamAvailable()
        )).toList());
        result.put("lastCheckedAt", addon.lastCheckedAt);
        result.put("lastError", addon.lastError);
        result.put("createdAt", addon.createdAt);
        return result;
    }

    private void applyManifest(CommunityAddon addon, JsonNode manifest) {
        addon.addonKey = manifest.path("id").asText().strip();
        addon.name = manifest.path("name").asText().strip();
        addon.version = clean(manifest.path("version").asText(null));
        addon.description = clean(manifest.path("description").asText(null));
        try {
            addon.manifestJson = mapper.writeValueAsString(manifest);
        } catch (IOException exception) {
            throw ApiException.validation("Manifeste JSON invalide");
        }
        addon.lastCheckedAt = Instant.now();
    }

    private void validateManifest(JsonNode manifest) {
        if (!manifest.isObject()
                || manifest.path("id").asText().isBlank()
                || manifest.path("name").asText().isBlank()) {
            throw ApiException.validation("Le manifeste doit contenir id et name");
        }
        JsonNode catalogs = manifest.path("catalogs");
        boolean supported = false;
        if (catalogs.isArray()) {
            for (JsonNode catalog : catalogs) {
                if (!mappedTypes(manifest, catalog).isEmpty() && !catalog.path("id").asText().isBlank()) {
                    supported = true;
                }
            }
        }
        boolean streamOnly = resourceAvailable(manifest, "stream") && !declaredTypes(manifest).isEmpty();
        if (!supported && !streamOnly) {
            throw ApiException.validation("Aucun catalogue ou flux live, movie ou series compatible");
        }
    }

    private List<CatalogDefinition> catalogs(CommunityAddon addon) {
        try {
            JsonNode manifest = mapper.readTree(addon.manifestJson);
            List<CatalogDefinition> result = new ArrayList<>();
            for (JsonNode catalog : manifest.path("catalogs")) {
                String remoteType = catalog.path("type").asText();
                String id = catalog.path("id").asText();
                if (id.isBlank()) {
                    continue;
                }
                for (String type : mappedTypes(manifest, catalog)) {
                    result.add(new CatalogDefinition(
                            remoteType,
                            type,
                            id,
                            firstText(catalog, "name", "title", addon.name),
                            catalogFilter(catalog),
                            resourceAvailable(manifest, "meta"),
                            resourceAvailable(manifest, "stream")
                    ));
                }
            }
            return result;
        } catch (IOException exception) {
            throw ApiException.validation("Manifeste enregistre invalide");
        }
    }

    private boolean hasCatalogs(CommunityAddon addon) {
        try {
            return !catalogs(addon).isEmpty();
        } catch (ApiException exception) {
            logAddonUnavailable(addon, exception);
            return false;
        }
    }

    private CatalogDefinition requireCatalog(CommunityAddon addon, String type, String catalogId) {
        return catalogs(addon).stream()
                .filter(catalog -> catalog.type().equals(type) && catalog.id().equals(catalogId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Catalogue d'add-on introuvable"));
    }

    private Map<String, Object> itemPayload(
            CommunityAddon addon,
            CatalogDefinition catalog,
            JsonNode value
    ) {
        String remoteId = text(value, "id");
        if (remoteId == null) {
            throw ApiException.validation("Un contenu de l'add-on ne possede pas d'identifiant");
        }
        String type = itemType(value, catalog);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", publicItemId(addon.id, type, catalog.id(), remoteId));
        payload.put("name", firstText(value, "name", "title", "Sans titre"));
        payload.put("type", type);
        payload.put("categoryId", categoryId(addon.id, catalog.type(), catalog.id()));
        payload.put("categoryName", catalog.name());
        payload.put("image", text(value, "poster", "logo", "thumbnail"));
        payload.put("poster", text(value, "poster", "thumbnail"));
        payload.put("backdrop", text(value, "background", "backdrop"));
        payload.put("summary", text(value, "description", "overview"));
        payload.put("releaseYear", text(value, "releaseInfo", "year"));
        payload.put("source", addon.name);
        payload.put("addonKey", addon.addonKey);
        payload.put("license", addon.licenseName);
        payload.put("adult", addon.adultContent);
        payload.put("privateUse", addon.privateUse);
        payload.put("privateAccess", addon.privateUse);
        payload.put("ownerId", addon.owner == null ? null : addon.owner.id);
        payload.put("isSeries", "series".equals(type));
        payload.put("metadataAvailable", catalog.metadataAvailable());
        payload.put("streamAvailable", catalog.streamAvailable());
        images.rewrite(payload);
        return payload;
    }

    private JsonNode metadata(CommunityAddon addon, CatalogDefinition catalog, AddonItemId itemId) {
        if (!catalog.metadataAvailable()) {
            return catalogItem(addon, catalog, itemId.remoteId());
        }
        String resourceType = resourceType(addon, catalog, "meta", itemId.type());
        JsonNode response = fetchJson(endpoint(addon, "meta", resourceType, itemId.remoteId()), true);
        JsonNode meta = response.has("meta") ? response.path("meta") : response;
        if (meta.isObject()) {
            return withRequestedIdentity(meta, itemId);
        }
        return catalogItem(addon, catalog, itemId.remoteId());
    }

    private JsonNode withRequestedIdentity(JsonNode meta, AddonItemId itemId) {
        if (!meta.isObject() || text(meta, "id") != null) {
            return meta;
        }
        ObjectNode copy = meta.deepCopy();
        copy.put("id", itemId.remoteId());
        return copy;
    }

    private JsonNode catalogItem(CommunityAddon addon, CatalogDefinition catalog, String remoteId) {
        if (catalog.searchOnly()) {
            throw ApiException.notFound("La fiche de ce catalogue de recherche n'est pas disponible");
        }
        Map<String, String> baseExtras = new LinkedHashMap<>();
        if (catalog.filterName() != null && !catalog.filterOptions().isEmpty()) {
            baseExtras.put(catalog.filterName(), catalog.filterOptions().get(0));
        }
        int pages = catalog.supportsSkip() ? MAX_ADDON_PAGES : 1;
        for (int page = 0; page < pages; page++) {
            Map<String, String> extras = new LinkedHashMap<>(baseExtras);
            if (page > 0) {
                extras.put("skip", String.valueOf(page * ADDON_PAGE_SIZE));
            }
            JsonNode response = fetchJson(
                    endpoint(addon, "catalog", catalog.remoteType(), catalog.id(), extras),
                    true
            );
            JsonNode values = response.has("metas") ? response.path("metas") : response.path("items");
            if (!values.isArray()) {
                break;
            }
            for (JsonNode value : values) {
                if (remoteId.equals(text(value, "id"))) {
                    return value;
                }
            }
            if (!catalog.supportsSkip() || values.size() < ADDON_PAGE_SIZE) {
                break;
            }
        }
        throw ApiException.notFound("Contenu introuvable dans le catalogue de l'add-on");
    }

    private String itemType(JsonNode value, CatalogDefinition catalog) {
        String remote = value.path("type").asText("").strip().toLowerCase(Locale.ROOT);
        return SUPPORTED_TYPES.contains(remote) ? remote : catalog.type();
    }

    private JsonNode fetchJson(URI uri, boolean cached) {
        String key = uri.toString();
        CachedJson existing = responseCache.get(key);
        if (cached && existing != null && existing.expiresAt().isAfter(Instant.now())) {
            return existing.value();
        }
        int maxAttempts = 3;
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(cached ? Duration.ofSeconds(6) : Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "Nexora-Community-Addon/1.0")
                .GET()
                .build();
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream input = response.body()) {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        if (response.statusCode() >= 500 && attempt + 1 < maxAttempts) {
                            pauseBeforeRetry();
                            continue;
                        }
                        if (cached && usableStale(existing)) {
                            return existing.value();
                        }
                        throw ApiException.serviceUnavailable("L'add-on a repondu HTTP " + response.statusCode());
                    }
                    JsonNode value = mapper.readTree(readLimited(input));
                    if (cached) {
                        responseCache.put(key, new CachedJson(value, Instant.now().plus(cacheTtl)));
                    }
                    return value;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw ApiException.serviceUnavailable("Communication avec l'add-on interrompue");
            } catch (IOException exception) {
                if (attempt + 1 < maxAttempts) {
                    pauseBeforeRetry();
                    continue;
                }
                if (cached && usableStale(existing)) {
                    return existing.value();
                }
                throw ApiException.serviceUnavailable("Impossible de joindre l'add-on communautaire");
            }
        }
        if (cached && usableStale(existing)) {
            return existing.value();
        }
        throw ApiException.serviceUnavailable("Impossible de joindre l'add-on communautaire");
    }

    private boolean usableStale(CachedJson cached) {
        return cached != null && cached.expiresAt().plus(MAX_STALE_CACHE_AGE).isAfter(Instant.now());
    }

    private void pauseBeforeRetry() {
        try {
            Thread.sleep(1500);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Communication avec l'add-on interrompue");
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16_384];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxResponseBytes) {
                throw new IOException("Reponse d'add-on trop volumineuse");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private URI endpoint(CommunityAddon addon, String resource, String type, String id) {
        return endpoint(addon, resource, type, id, Map.of());
    }

    private URI endpoint(
            CommunityAddon addon,
            String resource,
            String type,
            String id,
            Map<String, String> extras
    ) {
        URI manifest = safeUri(addon.manifestUrl, true);
        URI base = manifest.resolve(".");
        String extraPath = extras.isEmpty() ? "" : "/" + extras.entrySet().stream()
                .map(entry -> encodePath(entry.getKey()) + "=" + encodePath(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
        return safeUri(base.resolve(
                resource + "/" + encodePath(type) + "/" + encodePath(id) + extraPath + ".json"
        ).toString(), true);
    }

    private URI safeUri(String value, boolean requireHttps) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https")
                    || (!requireHttps && scheme.equals("http"))
                    || (allowPrivateHosts && scheme.equals("http")))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            if (!allowPrivateHosts) {
                for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                    if (address.isAnyLocalAddress()
                            || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()
                            || address.isSiteLocalAddress()
                            || address.isMulticastAddress()) {
                        throw new IllegalArgumentException();
                    }
                }
            }
            return uri;
        } catch (IOException | IllegalArgumentException exception) {
            throw ApiException.validation(requireHttps
                    ? "Le manifeste doit utiliser une URL HTTPS publique"
                    : "URL distante non autorisee");
        }
    }

    private String publicItemId(Long addonId, String type, String catalogId, String remoteId) {
        return ITEM_PREFIX + addonId + "~" + normalizeType(type) + "~"
                + encodeToken(catalogId) + "~" + encodeToken(remoteId);
    }

    private AddonItemId parseItemId(String value) {
        try {
            String[] parts = value.split("~", 5);
            if (parts.length != 5 || !"addon".equals(parts[0])) {
                throw new IllegalArgumentException();
            }
            return new AddonItemId(
                    Long.parseLong(parts[1]),
                    normalizeType(parts[2]),
                    decodeToken(parts[3]),
                    decodeToken(parts[4])
            );
        } catch (RuntimeException exception) {
            throw ApiException.validation("Identifiant d'add-on invalide");
        }
    }

    private String categoryId(Long addonId, String type, String catalogId) {
        return "addon-" + addonId + "-" + type + "-" + encodeToken(catalogId);
    }

    private String normalizedHosts(String value) {
        return String.join(",", parseHosts(value));
    }

    private Set<String> parseHosts(String value) {
        Set<String> hosts = new LinkedHashSet<>();
        for (String raw : String.valueOf(value == null ? "" : value).split("[,\\s]+")) {
            String host = raw.strip().toLowerCase(Locale.ROOT);
            if ("*".equals(host)) {
                hosts.add(host);
                continue;
            }
            if (host.startsWith("*.")) {
                host = "." + host.substring(2);
            }
            if (host.matches("^\\.?[a-z0-9.-]+$") && !host.isBlank()) {
                hosts.add(host);
            }
        }
        return hosts;
    }

    private boolean hostAllowed(String host, Set<String> allowed) {
        if (host == null) {
            return false;
        }
        if (allowed.contains("*")) {
            return true;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return allowed.stream().anyMatch(candidate -> candidate.startsWith(".")
                ? normalized.endsWith(candidate) && normalized.length() > candidate.length()
                : normalized.equals(candidate));
    }

    private void invalidate(CommunityAddon addon) {
        URI base = URI.create(addon.manifestUrl).resolve(".");
        responseCache.keySet().removeIf(key -> key.startsWith(base.toString()));
    }

    private boolean matchesQuery(Map<String, Object> item, String query) {
        return query == null || query.isBlank()
                || String.valueOf(item.get("name")).toLowerCase(Locale.ROOT)
                .contains(query.strip().toLowerCase(Locale.ROOT));
    }

    private String normalizeType(String type) {
        String normalized = type == null ? "movie" : type.strip().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(normalized)) {
            throw ApiException.validation("Type de contenu d'add-on non pris en charge");
        }
        return normalized;
    }

    private List<String> mappedTypes(JsonNode manifest, JsonNode catalog) {
        String remoteType = catalog.path("type").asText();
        String normalizedRemote = remoteType == null ? "" : remoteType.strip().toLowerCase(Locale.ROOT);
        if (SUPPORTED_TYPES.contains(normalizedRemote)) {
            return List.of(normalizedRemote);
        }
        String id = catalog.path("id").asText("").strip().toLowerCase(Locale.ROOT);
        String name = catalog.path("name").asText("").strip().toLowerCase(Locale.ROOT);
        String hint = normalizedRemote + " " + id + " " + name;
        if (hint.contains("porn") || hint.contains("adult") || hint.contains("xxx")) {
            return List.of("movie");
        }
        if (normalizedRemote.contains("anime")) {
            return id.contains("movie") || name.contains("movie")
                    ? List.of("movie")
                    : List.of("series");
        }
        if (normalizedRemote.contains("marvel")) {
            if ("series".equals(id)) {
                return List.of("series");
            }
            if ("movies".equals(id) || "xmen".equals(id)) {
                return List.of("movie");
            }
            return List.of("movie", "series");
        }
        Set<String> declaredTypes = declaredTypes(manifest);
        if (declaredTypes.size() == 1) {
            return List.copyOf(declaredTypes);
        }
        return declaredTypes.stream().sorted().toList();
    }

    private Set<String> declaredTypes(JsonNode manifest) {
        Set<String> declaredTypes = new LinkedHashSet<>();
        JsonNode types = manifest.path("types");
        if (types.isArray()) {
            types.forEach(type -> {
                String candidate = type.asText("").strip().toLowerCase(Locale.ROOT);
                if (SUPPORTED_TYPES.contains(candidate)) {
                    declaredTypes.add(candidate);
                }
            });
        }
        JsonNode resources = manifest.path("resources");
        if (resources.isArray()) {
            resources.forEach(resource -> {
                String name = resource.path("name").asText("");
                if (!"meta".equals(name) && !"stream".equals(name)) {
                    return;
                }
                JsonNode resourceTypes = resource.path("types");
                if (resourceTypes.isArray()) {
                    resourceTypes.forEach(type -> {
                        String candidate = type.asText("").strip().toLowerCase(Locale.ROOT);
                        if (SUPPORTED_TYPES.contains(candidate)) {
                            declaredTypes.add(candidate);
                        }
                    });
                }
            });
        }
        return declaredTypes;
    }

    private boolean streamResolverSupports(CommunityAddon addon, String itemType) {
        try {
            JsonNode manifest = mapper.readTree(addon.manifestJson);
            return resourceAvailable(manifest, "stream") && declaredTypes(manifest).contains(itemType);
        } catch (IOException exception) {
            throw ApiException.validation("Manifeste enregistre invalide");
        }
    }

    private String streamResourceType(CommunityAddon addon, String itemType) {
        try {
            JsonNode manifest = mapper.readTree(addon.manifestJson);
            Set<String> resourceTypes = resourceTypes(manifest, "stream");
            if (resourceTypes.contains(itemType)) {
                return itemType;
            }
            if (resourceTypes.size() == 1) {
                return resourceTypes.iterator().next();
            }
            Set<String> declaredTypes = declaredTypes(manifest);
            if (declaredTypes.contains(itemType)) {
                return itemType;
            }
            if (declaredTypes.size() == 1) {
                return declaredTypes.iterator().next();
            }
            return itemType;
        } catch (IOException exception) {
            throw ApiException.validation("Manifeste enregistre invalide");
        }
    }

    private Set<String> resourceTypes(JsonNode manifest, String resourceName) {
        Set<String> values = new LinkedHashSet<>();
        JsonNode resources = manifest.path("resources");
        if (!resources.isArray()) {
            return values;
        }
        for (JsonNode resource : resources) {
            if (!resourceName.equals(resource.path("name").asText())) {
                continue;
            }
            JsonNode types = resource.path("types");
            if (!types.isArray()) {
                continue;
            }
            types.forEach(type -> {
                String candidate = type.asText("").strip().toLowerCase(Locale.ROOT);
                if (SUPPORTED_TYPES.contains(candidate)) {
                    values.add(candidate);
                }
            });
        }
        return values;
    }

    private boolean resourceAvailable(JsonNode manifest, String resourceName) {
        JsonNode resources = manifest.path("resources");
        if (!resources.isArray() || resources.isEmpty()) {
            return true;
        }
        for (JsonNode resource : resources) {
            if (resource.isTextual() && resourceName.equals(resource.asText())) {
                return true;
            }
            if (resourceName.equals(resource.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesIdPrefixes(CommunityAddon addon, String remoteId) {
        try {
            JsonNode manifest = mapper.readTree(addon.manifestJson);
            JsonNode prefixes = manifest.path("idPrefixes");
            if (!prefixes.isArray() || prefixes.isEmpty()) {
                return true;
            }
            for (JsonNode prefix : prefixes) {
                String value = prefix.asText("").strip();
                if (!value.isBlank() && remoteId.startsWith(value)) {
                    return true;
                }
            }
            return false;
        } catch (IOException exception) {
            throw ApiException.validation("Manifeste enregistre invalide");
        }
    }

    private List<String> streamRemoteIds(String remoteId) {
        Set<String> values = new LinkedHashSet<>();
        if (remoteId != null && !remoteId.isBlank()) {
            values.add(remoteId);
            if (remoteId.startsWith("tmdb_")) {
                values.add("tmdb:" + remoteId.substring("tmdb_".length()));
            } else if (remoteId.startsWith("tmdb:")) {
                values.add("tmdb_" + remoteId.substring("tmdb:".length()));
            }
        }
        return List.copyOf(values);
    }

    private String resourceType(
            CommunityAddon addon,
            CatalogDefinition catalog,
            String resourceName,
            String itemType
    ) {
        try {
            JsonNode manifest = mapper.readTree(addon.manifestJson);
            Set<String> resourceTypes = new LinkedHashSet<>();
            for (JsonNode resource : manifest.path("resources")) {
                if (!resourceName.equals(resource.path("name").asText())) {
                    continue;
                }
                resource.path("types").forEach(type ->
                        resourceTypes.add(type.asText("").strip().toLowerCase(Locale.ROOT))
                );
            }
            if (resourceTypes.contains(itemType)) {
                return itemType;
            }
            String normalizedRemote = catalog.remoteType().strip().toLowerCase(Locale.ROOT);
            if (resourceTypes.contains(normalizedRemote)) {
                return catalog.remoteType();
            }
            Set<String> manifestTypes = new LinkedHashSet<>();
            manifest.path("types").forEach(type ->
                    manifestTypes.add(type.asText("").strip().toLowerCase(Locale.ROOT))
            );
            if (manifestTypes.contains(itemType)) {
                return itemType;
            }
            if (!SUPPORTED_TYPES.contains(normalizedRemote)) {
                return catalog.remoteType();
            }
            return itemType;
        } catch (IOException exception) {
            throw ApiException.validation("Manifeste enregistre invalide");
        }
    }

    private String encodeToken(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeToken(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private String text(JsonNode value, String... fields) {
        for (String field : fields) {
            JsonNode candidate = value.path(field);
            if (candidate.isValueNode() && !candidate.asText().isBlank()) {
                return candidate.asText().strip();
            }
        }
        return null;
    }

    private String firstText(JsonNode value, String first, String second, String fallback) {
        String result = text(value, first, second);
        return result == null ? fallback : result;
    }

    private List<String> stringList(JsonNode value) {
        if (value.isArray()) {
            List<String> result = new ArrayList<>();
            value.forEach(entry -> {
                if (entry.isValueNode() && !entry.asText().isBlank()) {
                    result.add(entry.asText().strip());
                }
            });
            return result;
        }
        if (value.isTextual()) {
            return List.of(value.asText());
        }
        return List.of();
    }

    private CatalogFilter catalogFilter(JsonNode catalog) {
        JsonNode optionalSearch = null;
        for (JsonNode extra : catalog.path("extra")) {
            String name = extra.path("name").asText("").strip();
            if (name.isBlank() || "skip".equals(name)) {
                continue;
            }
            if ("search".equals(name)) {
                optionalSearch = extra;
                continue;
            }
            return new CatalogFilter(
                    name,
                    stringList(extra.path("options")),
                    extra.path("isRequired").asBoolean(false),
                    false,
                    hasExtra(catalog, "skip")
            );
        }
        if (optionalSearch != null && optionalSearch.path("isRequired").asBoolean(false)) {
            return new CatalogFilter("search", List.of(), true, true, hasExtra(catalog, "skip"));
        }
        return new CatalogFilter(null, List.of(), false, false, hasExtra(catalog, "skip"));
    }

    private boolean hasExtra(JsonNode catalog, String name) {
        for (JsonNode extra : catalog.path("extra")) {
            if (name.equals(extra.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private void logAddonUnavailable(CommunityAddon addon, ApiException exception) {
        LOGGER.warn(
                "Add-on communautaire ignore pendant le chargement du catalogue {} ({}): {}",
                addon.id,
                addon.name,
                exception.getMessage()
        );
    }

    private record CatalogFilter(
            String name,
            List<String> options,
            boolean required,
            boolean searchOnly,
            boolean supportsSkip
    ) {
    }

    private record CatalogDefinition(
            String remoteType,
            String type,
            String id,
            String name,
            CatalogFilter filter,
            boolean metadataAvailable,
            boolean streamAvailable
    ) {
        String filterName() {
            return filter.name();
        }

        List<String> filterOptions() {
            return filter.options();
        }

        boolean filterRequired() {
            return filter.required();
        }

        boolean searchOnly() {
            return filter.searchOnly();
        }

        boolean supportsSkip() {
            return filter.supportsSkip();
        }
    }

    private record StreamResolution(boolean available, String url, String reason) {
        static StreamResolution available(String url) {
            return new StreamResolution(true, url, null);
        }

        static StreamResolution unavailable(String reason) {
            return new StreamResolution(false, null, reason);
        }
    }

    private record AddonItemId(Long addonId, String type, String catalogId, String remoteId) {
    }

    private record CachedJson(JsonNode value, Instant expiresAt) {
    }
}
