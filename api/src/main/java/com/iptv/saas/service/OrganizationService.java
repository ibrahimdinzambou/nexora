package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.OrganizationMembership;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.OrganizationMembershipRepository;
import com.iptv.saas.repository.OrganizationRepository;
import com.iptv.saas.repository.UserRepository;
import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class OrganizationService {
    private final OrganizationRepository organizations;
    private final OrganizationMembershipRepository memberships;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public OrganizationService(
            OrganizationRepository organizations,
            OrganizationMembershipRepository memberships,
            UserRepository users,
            PasswordEncoder passwordEncoder,
            AuditService audit
    ) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Organization> accessibleOrganizations(UserEntity user) {
        if (SecurityUtils.isAdminLike(user)) {
            return organizations.findAll();
        }
        List<Organization> result = new ArrayList<>(organizations.findByOwner(user));
        memberships.findByUser(user).stream()
                .map(membership -> membership.organization)
                .filter(org -> result.stream().noneMatch(existing -> existing.id.equals(org.id)))
                .forEach(result::add);
        return result;
    }

    @Transactional(readOnly = true)
    public Organization currentOrganization(UserEntity user) {
        if (user.currentOrganization != null) {
            return organizations.findById(user.currentOrganization.id)
                    .orElseThrow(() -> ApiException.notFound("Organisation courante introuvable"));
        }
        return accessibleOrganizations(user).stream().findFirst()
                .orElseThrow(() -> ApiException.validation("Aucune organisation associee a l'utilisateur"));
    }

    @Transactional(readOnly = true)
    public Organization requireAccess(UserEntity user, Long organizationId) {
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> ApiException.notFound("Organisation introuvable"));
        if (SecurityUtils.isAdminLike(user)
                || (organization.owner != null && organization.owner.id.equals(user.id))
                || memberships.existsByOrganizationAndUser(organization, user)) {
            return organization;
        }
        throw ApiException.forbidden("Acces organisation refuse");
    }

    @Transactional
    public Organization createOrganization(UserEntity owner, String name, String billingEmail) {
        Organization organization = new Organization();
        organization.name = name;
        organization.slug = uniqueSlug(name);
        organization.owner = owner;
        organization.billingEmail = billingEmail == null || billingEmail.isBlank() ? owner.email : billingEmail;
        organization.status = Enums.OrganizationStatus.ACTIVE;
        organization = organizations.save(organization);

        OrganizationMembership membership = new OrganizationMembership();
        membership.organization = organization;
        membership.user = owner;
        membership.role = "owner";
        memberships.save(membership);

        if (owner.currentOrganization == null) {
            owner.currentOrganization = organization;
            users.save(owner);
        }
        audit.log(owner, "organization.created", "Organization", organization.id, organization.name);
        return organization;
    }

    @Transactional
    public Organization updateOrganization(UserEntity user, Long id, String name, String billingEmail, Enums.OrganizationStatus status) {
        Organization organization = requireAccess(user, id);
        if (name != null && !name.isBlank()) {
            organization.name = name;
        }
        if (billingEmail != null) {
            organization.billingEmail = billingEmail;
        }
        if (status != null && SecurityUtils.isAdminLike(user)) {
            organization.status = status;
        }
        audit.log(user, "organization.updated", "Organization", organization.id, organization.name);
        return organizations.save(organization);
    }

    @Transactional
    public OrganizationMembership addMember(UserEntity actor, Long organizationId, String email, String name, String role) {
        Organization organization = requireAccess(actor, organizationId);
        UserEntity user = users.findByEmailIgnoreCase(email).orElseGet(() -> {
            UserEntity created = new UserEntity();
            created.email = email.toLowerCase(Locale.ROOT);
            created.name = name == null || name.isBlank() ? email : name;
            created.passwordHash = passwordEncoder.encode("password");
            created.role = Enums.UserRole.USER;
            created.active = true;
            created.emailVerified = false;
            return users.save(created);
        });
        OrganizationMembership membership = memberships.findByOrganizationAndUser(organization, user)
                .orElseGet(OrganizationMembership::new);
        membership.organization = organization;
        membership.user = user;
        membership.role = role == null || role.isBlank() ? "member" : role;
        membership.status = "active";
        if (user.currentOrganization == null) {
            user.currentOrganization = organization;
            users.save(user);
        }
        audit.log(actor, "organization.member.added", "User", user.id, organization.name);
        return memberships.save(membership);
    }

    @Transactional
    public OrganizationMembership updateMember(UserEntity actor, Long organizationId, Long userId, String role, String status) {
        Organization organization = requireAccess(actor, organizationId);
        UserEntity member = users.findById(userId).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        OrganizationMembership membership = memberships.findByOrganizationAndUser(organization, member)
                .orElseThrow(() -> ApiException.notFound("Membre introuvable"));
        if (role != null && !role.isBlank()) {
            membership.role = role;
        }
        if (status != null && !status.isBlank()) {
            membership.status = status;
        }
        audit.log(actor, "organization.member.updated", "User", member.id, organization.name);
        return memberships.save(membership);
    }

    @Transactional
    public void removeMember(UserEntity actor, Long organizationId, Long userId) {
        Organization organization = requireAccess(actor, organizationId);
        UserEntity member = users.findById(userId).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        if (organization.owner != null && organization.owner.id.equals(member.id)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Le proprietaire ne peut pas etre retire", "owner_cannot_be_removed");
        }
        memberships.deleteByOrganizationAndUser(organization, member);
        audit.log(actor, "organization.member.removed", "User", member.id, organization.name);
    }

    private String uniqueSlug(String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "organization";
        }
        String candidate = base;
        int i = 2;
        while (organizations.findBySlug(candidate).isPresent()) {
            candidate = base + "-" + i++;
        }
        return candidate;
    }
}
