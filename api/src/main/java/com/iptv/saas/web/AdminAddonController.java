package com.iptv.saas.web;

import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.AuditService;
import com.iptv.saas.service.CommunityAddonService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/addons")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminAddonController {
    private final CommunityAddonService addons;
    private final AuditService audit;

    public AdminAddonController(CommunityAddonService addons, AuditService audit) {
        this.addons = addons;
        this.audit = audit;
    }

    @GetMapping
    public Object list() {
        return Responses.ok(addons.list(SecurityUtils.currentUser()));
    }

    @PostMapping
    public Object install(@Valid @RequestBody AddonRequest request) {
        var actor = SecurityUtils.currentUser();
        var addon = addons.install(
                request.manifestUrl(),
                request.allowedStreamHosts(),
                request.licenseName(),
                request.licenseUrl(),
                request.adultContent(),
                request.privateUse(),
                actor
        );
        audit.log(actor, "addon.installed", "CommunityAddon", addon.id, addon.manifestUrl);
        return Responses.ok(addons.list(actor).stream()
                .filter(item -> addon.id.equals(item.get("id")))
                .findFirst()
                .orElseThrow());
    }

    @PutMapping("/{id}")
    public Object update(@PathVariable Long id, @RequestBody AddonRequest request) {
        var actor = SecurityUtils.currentUser();
        var addon = addons.update(
                id,
                request.manifestUrl(),
                request.allowedStreamHosts(),
                request.licenseName(),
                request.licenseUrl(),
                request.adultContent(),
                request.privateUse(),
                actor
        );
        audit.log(actor, "addon.updated", "CommunityAddon", addon.id, addon.allowedStreamHosts);
        return Responses.ok(addon.id);
    }

    @PostMapping("/{id}/approve")
    public Object approve(@PathVariable Long id) {
        var actor = SecurityUtils.currentUser();
        var addon = addons.approve(id, actor);
        audit.log(actor, "addon.approved", "CommunityAddon", addon.id, addon.addonKey);
        return Responses.ok(addon.id);
    }

    @PostMapping("/{id}/disable")
    public Object disable(@PathVariable Long id) {
        var actor = SecurityUtils.currentUser();
        var addon = addons.disable(id, actor);
        audit.log(actor, "addon.disabled", "CommunityAddon", addon.id, addon.addonKey);
        return Responses.ok(addon.id);
    }

    @PostMapping("/{id}/refresh")
    public Object refresh(@PathVariable Long id) {
        var actor = SecurityUtils.currentUser();
        var addon = addons.refresh(id, actor);
        audit.log(actor, "addon.refreshed", "CommunityAddon", addon.id, addon.version);
        return Responses.ok(addon.id);
    }

    @PostMapping("/{id}/access")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Object access(@PathVariable Long id, @RequestBody AccessRequest request) {
        var actor = SecurityUtils.currentUser();
        var addon = addons.updateAccess(id, request.userIds(), actor);
        audit.log(actor, "addon.access.updated", "CommunityAddon", addon.id, String.valueOf(request.userIds()));
        return Responses.ok(addon.id);
    }

    @DeleteMapping("/{id}")
    public Object delete(@PathVariable Long id) {
        var actor = SecurityUtils.currentUser();
        addons.delete(id, actor);
        audit.log(actor, "addon.deleted", "CommunityAddon", id, null);
        return Responses.ok(id);
    }

    public record AddonRequest(
            @NotBlank String manifestUrl,
            String allowedStreamHosts,
            String licenseName,
            String licenseUrl,
            boolean adultContent,
            boolean privateUse
    ) {
    }

    public record AccessRequest(List<Long> userIds) {
    }
}
