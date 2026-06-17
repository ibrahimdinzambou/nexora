package com.iptv.saas.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.domain.UserEntity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CatalogCategoryAccess {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private CatalogCategoryAccess() {
    }

    public static Set<String> allowedCategoryIds(UserEntity user) {
        if (user == null || user.allowedCategories == null || user.allowedCategories.isBlank()) {
            return Set.of();
        }
        try {
            return new LinkedHashSet<>(MAPPER.readValue(user.allowedCategories, STRING_LIST));
        } catch (Exception exception) {
            return Set.of();
        }
    }

    public static boolean permits(UserEntity user, String categoryId) {
        Set<String> allowed = allowedCategoryIds(user);
        return allowed.isEmpty() || categoryId == null || allowed.contains(categoryId);
    }

    public static boolean permitsExplicitly(UserEntity user, String categoryId) {
        return categoryId != null && allowedCategoryIds(user).contains(categoryId);
    }

    public static List<Map<String, Object>> filter(UserEntity user, List<Map<String, Object>> values) {
        Set<String> allowed = allowedCategoryIds(user);
        return values.stream()
                .filter(value -> {
                    String categoryId = String.valueOf(value.get("categoryId") != null
                            ? value.get("categoryId")
                            : value.get("id"));
                    boolean adult = Boolean.TRUE.equals(value.get("adult"));
                    return adult ? allowed.contains(categoryId) : allowed.isEmpty() || allowed.contains(categoryId);
                })
                .toList();
    }
}
