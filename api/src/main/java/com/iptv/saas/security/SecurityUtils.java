package com.iptv.saas.security;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.web.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {
    private SecurityUtils() {
    }

    public static UserEntity currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserEntity user)) {
            throw ApiException.unauthorized("Authentification requise");
        }
        return user;
    }

    public static boolean isAdminLike(UserEntity user) {
        return user != null && (user.role == Enums.UserRole.SUPER_ADMIN
                || user.role == Enums.UserRole.ADMIN
                || user.role == Enums.UserRole.BILLING
                || user.role == Enums.UserRole.SUPPORT
                || user.role == Enums.UserRole.OPS);
    }
}
