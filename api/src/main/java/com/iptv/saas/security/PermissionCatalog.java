package com.iptv.saas.security;

import com.iptv.saas.domain.Enums;

import java.util.Set;

public final class PermissionCatalog {
    private PermissionCatalog() {
    }

    public static Set<String> permissionsFor(Enums.UserRole role) {
        if (role == Enums.UserRole.SUPER_ADMIN || role == Enums.UserRole.ADMIN) {
            return Set.of(
                    "admin.access", "customer.read", "customer.write",
                    "billing.read", "billing.write", "invoice.read",
                    "support.read", "support.write", "ops.read", "ops.write"
            );
        }
        if (role == Enums.UserRole.BILLING) {
            return Set.of("admin.access", "billing.read", "billing.write", "invoice.read", "customer.read");
        }
        if (role == Enums.UserRole.SUPPORT) {
            return Set.of("admin.access", "support.read", "support.write", "customer.read");
        }
        if (role == Enums.UserRole.OPS) {
            return Set.of("admin.access", "ops.read", "ops.write");
        }
        return Set.of();
    }
}
