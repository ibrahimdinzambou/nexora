package com.iptv.saas.security;

import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.web.ApiMappers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogCategoryAccessTests {
    @Test
    void parsesFiltersAndExposesAllowedCategories() {
        UserEntity user = new UserEntity();
        user.allowedCategories = "[\"sports\",\"movies-action\"]";

        List<Map<String, Object>> filtered = CatalogCategoryAccess.filter(user, List.of(
                Map.of("id", "sports", "name", "Sports"),
                Map.of("id", "news", "name", "News")
        ));

        assertEquals(List.of("sports", "movies-action"), List.copyOf(CatalogCategoryAccess.allowedCategoryIds(user)));
        assertEquals(1, filtered.size());
        assertEquals("sports", filtered.get(0).get("id"));
        assertEquals(CatalogCategoryAccess.allowedCategoryIds(user), ApiMappers.user(user).get("allowedCategories"));
    }

    @Test
    void emptyConfigurationLeavesTheCatalogUnrestricted() {
        UserEntity user = new UserEntity();
        user.allowedCategories = "[]";

        assertTrue(CatalogCategoryAccess.permits(user, "news"));
    }

    @Test
    void adultCatalogRequiresAnExplicitCategoryGrant() {
        UserEntity user = new UserEntity();
        Map<String, Object> adult = Map.of(
                "id", "addon-item",
                "categoryId", "addon-7-movie-free",
                "adult", true
        );

        assertTrue(CatalogCategoryAccess.filter(user, List.of(adult)).isEmpty());

        user.allowedCategories = "[\"addon-7-movie-free\"]";
        assertEquals(1, CatalogCategoryAccess.filter(user, List.of(adult)).size());
    }
}
