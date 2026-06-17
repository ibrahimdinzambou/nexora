package com.iptv.saas.web;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.repository.OrganizationMembershipRepository;
import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.OrganizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {
    private final OrganizationService organizations;
    private final OrganizationMembershipRepository memberships;

    public OrganizationController(OrganizationService organizations, OrganizationMembershipRepository memberships) {
        this.organizations = organizations;
        this.memberships = memberships;
    }

    @GetMapping
    public Object list() {
        return Responses.ok(organizations.accessibleOrganizations(SecurityUtils.currentUser()).stream()
                .map(ApiMappers::organization)
                .toList());
    }

    @PostMapping
    public Object create(@Valid @RequestBody OrganizationRequest request) {
        return Responses.ok(ApiMappers.organization(organizations.createOrganization(
                SecurityUtils.currentUser(),
                request.name(),
                request.billingEmail()
        )));
    }

    @GetMapping("/{id}")
    public Object detail(@PathVariable Long id) {
        return Responses.ok(ApiMappers.organization(organizations.requireAccess(SecurityUtils.currentUser(), id)));
    }

    @PutMapping("/{id}")
    public Object update(@PathVariable Long id, @RequestBody OrganizationRequest request) {
        return Responses.ok(ApiMappers.organization(organizations.updateOrganization(
                SecurityUtils.currentUser(),
                id,
                request.name(),
                request.billingEmail(),
                request.status()
        )));
    }

    @GetMapping("/{id}/members")
    public Object members(@PathVariable Long id) {
        var organization = organizations.requireAccess(SecurityUtils.currentUser(), id);
        return Responses.ok(memberships.findByOrganization(organization).stream().map(ApiMappers::membership).toList());
    }

    @PostMapping("/{id}/members")
    public Object addMember(@PathVariable Long id, @Valid @RequestBody MemberRequest request) {
        return Responses.ok(ApiMappers.membership(organizations.addMember(
                SecurityUtils.currentUser(),
                id,
                request.email(),
                request.name(),
                request.role()
        )));
    }

    @PatchMapping("/{id}/members/{userId}")
    public Object updateMember(@PathVariable Long id, @PathVariable Long userId, @RequestBody MemberPatch request) {
        return Responses.ok(ApiMappers.membership(organizations.updateMember(
                SecurityUtils.currentUser(),
                id,
                userId,
                request.role(),
                request.status()
        )));
    }

    @DeleteMapping("/{id}/members/{userId}")
    public Object removeMember(@PathVariable Long id, @PathVariable Long userId) {
        organizations.removeMember(SecurityUtils.currentUser(), id, userId);
        return Responses.message("Membre retire");
    }

    public record OrganizationRequest(@NotBlank String name, @Email String billingEmail, Enums.OrganizationStatus status) {
    }

    public record MemberRequest(@Email @NotBlank String email, String name, String role) {
    }

    public record MemberPatch(String role, String status) {
    }
}
