package com.iptv.saas.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.security.BearerTokenFilter;
import com.iptv.saas.web.Responses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private static final String[] API_DOCS_MATCHERS = {
            "/api/docs",
            "/api/documentation",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml"
    };

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            BearerTokenFilter bearerTokenFilter,
            ObjectMapper mapper,
            @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled,
            @Value("${app.security.public-api-docs:false}") boolean publicApiDocs
    )
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> {
                    headers.frameOptions(frame -> frame.sameOrigin());
                    headers.contentTypeOptions(Customizer.withDefaults());
                    headers.httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .preload(true)
                            .maxAgeInSeconds(31_536_000)
                    );
                    headers.referrerPolicy(referrer -> referrer.policy(
                            ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN
                    ));
                    headers.permissionsPolicy(policy -> policy.policy(
                            "camera=(), microphone=(), geolocation=(), payment=()"
                    ));
                    headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                            "default-src 'self'; "
                                    + "base-uri 'self'; "
                                    + "form-action 'self'; "
                                    + "frame-ancestors 'self'; "
                                    + "img-src 'self' https: data: blob:; "
                                    + "media-src 'self' https: http: blob:; "
                                    + "connect-src 'self' https: http:; "
                                    + "script-src 'self'; "
                                    + "style-src 'self' https://fonts.googleapis.com 'unsafe-inline'; "
                                    + "font-src 'self' https://fonts.gstatic.com data:"
                    ));
                })
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(401);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    mapper.writeValue(response.getOutputStream(), Responses.error("Authentification requise", "unauthorized"));
                }))
                .authorizeHttpRequests(auth -> {
                    if (h2ConsoleEnabled) {
                        auth.requestMatchers("/h2-console/**").permitAll();
                    }
                    if (publicApiDocs) {
                        auth.requestMatchers(API_DOCS_MATCHERS).permitAll();
                    } else {
                        auth.requestMatchers(API_DOCS_MATCHERS)
                                .hasAnyRole("SUPER_ADMIN", "ADMIN", "OPS");
                    }
                    auth.requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/2fa/verify",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password",
                                "/api/auth/email/verify",
                                "/api/auth/email/resend"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/billing/plans", "/api/billing/payment-methods").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/billing/plans", "/api/v1/catalog/categories").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/catalog/images/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/stream/proxy/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/stream/hls/**").permitAll()
                        .requestMatchers("/api/admin/**").hasAnyRole("SUPER_ADMIN", "ADMIN", "BILLING", "SUPPORT", "OPS")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll();
                })
                .addFilterBefore(bearerTokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
