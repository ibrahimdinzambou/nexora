package com.iptv.saas.security;

import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class BearerTokenFilter extends OncePerRequestFilter {
    private final TokenService tokenService;
    private final boolean requireEmailVerification;

    public BearerTokenFilter(
            TokenService tokenService,
            @Value("${app.security.require-email-verification:true}") boolean requireEmailVerification
    ) {
        this.tokenService = tokenService;
        this.requireEmailVerification = requireEmailVerification;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String rawToken = header.substring(7).trim();
            tokenService.findValidUser(rawToken).filter(this::canAuthenticate).ifPresent(this::authenticate);
        }
        filterChain.doFilter(request, response);
    }

    private boolean canAuthenticate(UserEntity user) {
        return user.active && (!requireEmailVerification || user.emailVerified);
    }

    private void authenticate(UserEntity user) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.role.name()));
        PermissionCatalog.permissionsFor(user.role).forEach(permission ->
                authorities.add(new SimpleGrantedAuthority("PERM_" + permission))
        );
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
