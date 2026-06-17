package com.iptv.saas.web;

import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.CommunityAddonService;
import com.iptv.saas.service.IptvCatalogService;
import com.iptv.saas.service.SubscriptionAccessService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class CatalogController {
    private final IptvCatalogService catalog;
    private final CommunityAddonService addons;
    private final SubscriptionAccessService access;

    public CatalogController(IptvCatalogService catalog, CommunityAddonService addons, SubscriptionAccessService access) {
        this.catalog = catalog;
        this.addons = addons;
        this.access = access;
    }

    @GetMapping({"/api/catalog/categories", "/api/v1/catalog/categories"})
    public Object categories(@RequestParam(required = false) String type) {
        UserEntity user = currentUser();
        List<Map<String, Object>> values = new ArrayList<>();
        if (catalog.hasActiveSources() || !addons.hasApprovedAddons(user)) {
            values.addAll(catalog.categories(type));
        }
        values.addAll(addons.categories(type, user));
        List<Map<String, Object>> uniqueValues = unique(values, "id");
        return Responses.ok(SecurityUtils.isAdminLike(user)
                ? uniqueValues
                : access.filter(user, uniqueValues));
    }

    @GetMapping("/api/catalog/languages")
    public Object languages(@RequestParam(required = false) String type) {
        return Responses.ok(catalog.languages(type));
    }

    @GetMapping("/api/catalog/items")
    public Object items(
            @RequestParam(required = false, defaultValue = "live") String type,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String language,
            @RequestParam(required = false, defaultValue = "default") String sort,
            @RequestParam(required = false, defaultValue = "0") int limit,
            @RequestParam(required = false) String addonFilter,
            @RequestParam(required = false, defaultValue = "1") int addonPages
    ) {
        UserEntity user = currentUser();
        List<Map<String, Object>> values = new ArrayList<>();
        if (catalog.hasActiveSources() || !addons.hasApprovedAddons(user)) {
            values.addAll(catalog.items(type, q, categoryId, language, sort, limit));
        }
        if (shouldLoadAddons(q, categoryId, addonFilter)) {
            values.addAll(addons.items(type, q, categoryId, sort, limit, addonFilter, addonPages, user));
        }
        if (limit > 0 && values.size() > limit) {
            values = new ArrayList<>(values.subList(0, limit));
        }
        return Responses.ok(access.filter(user, values));
    }

    @GetMapping("/api/catalog/series/{seriesId}")
    public Object series(
            @PathVariable String seriesId,
            @RequestParam(required = false) String title
    ) {
        UserEntity user = currentUser();
        var series = addons.isAddonItem(seriesId)
                ? addons.seriesInfo(seriesId, user)
                : catalog.seriesInfo(seriesId, title);
        if (!permits(user, series)) {
            throw ApiException.forbidden("Cette catégorie n'est pas autorisée pour votre compte");
        }
        return Responses.ok(series);
    }

    @GetMapping("/api/catalog/items/{itemId}")
    public Object item(@PathVariable String itemId) {
        UserEntity user = currentUser();
        var item = addons.isAddonItem(itemId)
                ? addons.itemInfo(itemId, user)
                : catalog.itemInfo(itemId);
        if (!permits(user, item)) {
            throw ApiException.forbidden("Cette catégorie n'est pas autorisée pour votre compte");
        }
        return Responses.ok(item);
    }

    @GetMapping("/api/stream/groups")
    public Object groups() {
        return Responses.ok(access.filter(currentUser(), catalog.liveGroups()));
    }

    @GetMapping("/api/stream/channels")
    public Object channels() {
        return Responses.ok(access.filter(currentUser(), catalog.liveChannels()));
    }

    private UserEntity currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof UserEntity user ? user : null;
    }

    private boolean permits(UserEntity user, Map<String, Object> value) {
        return access.permits(user, value);
    }

    private boolean shouldLoadAddons(String q, String categoryId, String addonFilter) {
        return (q != null && !q.isBlank())
                || (categoryId != null && !categoryId.isBlank())
                || (addonFilter != null && !addonFilter.isBlank());
    }

    private List<Map<String, Object>> unique(List<Map<String, Object>> values, String key) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        values.forEach(value -> unique.putIfAbsent(String.valueOf(value.get(key)), value));
        return List.copyOf(unique.values());
    }
}
