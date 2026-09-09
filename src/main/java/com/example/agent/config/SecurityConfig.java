package com.example.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Auth gating, two filter chains:
 *
 *   /api/**  → API auth, mode-driven by {@code agent.auth.mode}:
 *                - {@code disabled} (or {@code agent.auth.enabled=false}): permitAll.
 *                - {@code basic}: HTTP Basic against the in-memory user.
 *                - {@code oidc}: JWT bearer auth (OAuth2 resource server) validated
 *                                against {@code agent.auth.oidc.issuer-uri}.
 *              {@code /api/health} stays open in every mode.
 *   /**      → OAuth2 login (GitHub / Google / Okta) when any provider is
 *               configured; falls back to Basic. Browser flow uses sessions.
 *               Not affected by {@code agent.auth.mode}.
 *
 * Both chains are skipped when {@code agent.auth.enabled=false}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final AgentProperties props;
    private final ClientRegistrationRepository clientRegistrations;

    public SecurityConfig(AgentProperties props,
                          ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
        this.props = props;
        this.clientRegistrations = clientRegistrations.getIfAvailable();
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.auth", name = "mode", havingValue = "basic", matchIfMissing = true)
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.auth", name = "mode", havingValue = "basic", matchIfMissing = true)
    public UserDetailsService users(PasswordEncoder encoder) {
        AgentProperties.Auth a = props.getAuth();
        String username = a.getUsername() == null ? "admin" : a.getUsername();
        return new InMemoryUserDetailsManager(
                User.withUsername(username)
                    .password(encoder.encode(resolvePassword(username, a.getPassword())))
                    .roles("USER")
                    .build());
    }

    /**
     * Issue #18: a shipped constant password must never authenticate. When no
     * password is configured — or it was left at the historically documented
     * "change-me" — generate a random one and log it once, Spring Security's
     * own first-run pattern. API clients that need a stable password set
     * AGENT_AUTH_PASSWORD.
     */
    private String resolvePassword(String username, String configured) {
        if (configured != null && !configured.isBlank() && !"change-me".equals(configured)) {
            return configured;
        }
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        String generated = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        log.warn("""

                ****************************************************************
                agent.auth.password is not set{} — generated a random password.

                    user: {}
                    password: {}

                Set AGENT_AUTH_PASSWORD to use a stable password.
                ****************************************************************""",
                "change-me".equals(configured) ? " (the known default 'change-me' is refused)" : "",
                username, generated);
        return generated;
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.auth", name = "mode", havingValue = "basic", matchIfMissing = true)
    public AuthenticationManager authManager(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    /**
     * The first thing oidc mode checks at startup, and the failure a mis-nested
     * {@code oidc:} block in application.yml produced: the documented
     * {@code AGENT_OIDC_ISSUER_URI} bound nothing under {@code agent.auth}.
     * Separate from {@link #jwtDecoder()} so {@code RelaxedEnvBindingTest} can run
     * the exact check against the real configuration file without building a
     * decoder — {@link JwtDecoders#fromIssuerLocation} fetches the issuer's
     * discovery document eagerly.
     */
    static String requireIssuerUri(AgentProperties.Oidc oidc) {
        if (oidc.getIssuerUri() == null || oidc.getIssuerUri().isBlank()) {
            throw new IllegalStateException(
                    "agent.auth.mode=oidc requires agent.auth.oidc.issuer-uri to be set.");
        }
        return oidc.getIssuerUri();
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.auth", name = "mode", havingValue = "oidc")
    public JwtDecoder jwtDecoder() {
        AgentProperties.Oidc oidc = props.getAuth().getOidc();
        String issuerUri = requireIssuerUri(oidc);
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator(Duration.ofSeconds(oidc.getClockSkewSeconds())));
        validators.add(new JwtIssuerValidator(issuerUri));
        if (oidc.getAudience() != null && !oidc.getAudience().isBlank()) {
            validators.add(jwt -> jwt.getAudience() != null && jwt.getAudience().contains(oidc.getAudience())
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                            "invalid_token", "Required audience '" + oidc.getAudience() + "' missing", null)));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.auth", name = "mode", havingValue = "oidc")
    public Converter<Jwt, AbstractAuthenticationToken> jwtAuthConverter() {
        String claim = props.getAuth().getOidc().getPrincipalClaim();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName(claim == null || claim.isBlank() ? "sub" : claim);
        return converter;
    }

    /** Programmatic clients: stateless. Highest precedence. */
    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(
            HttpSecurity http,
            ObjectProvider<JwtDecoder> jwtDecoderProvider,
            ObjectProvider<Converter<Jwt, AbstractAuthenticationToken>> jwtAuthConverterProvider) throws Exception {
        http.securityMatcher("/api/**")
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        String mode = props.getAuth().getMode();
        boolean disabled = !props.getAuth().isEnabled() || "disabled".equalsIgnoreCase(mode);

        if (disabled) {
            log.info("API auth DISABLED ({}={}).",
                    !props.getAuth().isEnabled() ? "agent.auth.enabled" : "agent.auth.mode",
                    !props.getAuth().isEnabled() ? "false" : mode);
            return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }

        http.authorizeHttpRequests(a -> a
                .requestMatchers("/api/health").permitAll()
                .anyRequest().authenticated());

        if ("oidc".equalsIgnoreCase(mode)) {
            log.info("API auth ENABLED (mode=oidc) - JWT bearer required.");
            JwtDecoder decoder = jwtDecoderProvider.getIfAvailable();
            Converter<Jwt, AbstractAuthenticationToken> converter = jwtAuthConverterProvider.getIfAvailable();
            http.oauth2ResourceServer(o -> o.jwt(jwt -> {
                if (converter != null) jwt.jwtAuthenticationConverter(converter);
                if (decoder != null) jwt.decoder(decoder);
            }));
        } else {
            log.info("API auth ENABLED (mode=basic) - HTTP Basic required.");
            http.httpBasic(Customizer.withDefaults());
        }
        return http.build();
    }

    /** Browser + UI: OAuth login when providers exist, Basic fallback always. */
    @Bean
    @Order(2)
    public SecurityFilterChain webFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults());

        if (!props.getAuth().isEnabled()) {
            log.info("Auth DISABLED (agent.auth.enabled=false) - all endpoints are open.");
            return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }

        List<String> registered = registeredProviders();
        if (registered.isEmpty()) {
            log.info("Auth ENABLED - HTTP Basic only (no OAuth registrations).");
        } else {
            log.info("Auth ENABLED - OAuth providers: {}. HTTP Basic also accepted.", registered);
        }

        // Health probes stay open in every mode: a load balancer cannot authenticate,
        // and the endpoint reports liveness/readiness only.
        //
        // Metrics and the API docs used to sit in this same list. Both describe the
        // deployment to anyone who can reach the port — metric labels carry tool
        // names, providers, token counts and request rates; the OpenAPI document
        // describes every endpoint and schema. Neither is needed by infrastructure,
        // so both now follow the auth setting. Scrapers that cannot authenticate
        // can reopen metrics alone with agent.metrics.public-scrape=true.
        boolean publicScrape = props.getMetrics().isPublicScrape();
        if (publicScrape) {
            log.warn("Metrics are readable WITHOUT authentication "
                    + "(agent.metrics.public-scrape=true). Restrict the port at the network.");
        }

        http.authorizeHttpRequests(a -> {
            a.requestMatchers("/actuator/health/**",
                              "/login/**", "/oauth2/**", "/error").permitAll();
            if (publicScrape) {
                a.requestMatchers("/actuator/prometheus").permitAll();
            }
            a.anyRequest().authenticated();
        })
            .httpBasic(Customizer.withDefaults())
            .logout(l -> l.logoutSuccessUrl("/").permitAll());

        if (clientRegistrations != null && !registered.isEmpty()) {
            http.oauth2Login(o -> o.defaultSuccessUrl("/", true));
        }
        return http.build();
    }

    private List<String> registeredProviders() {
        List<String> out = new ArrayList<>();
        if (clientRegistrations == null) return out;
        for (String id : List.of("github", "google", "okta")) {
            if (clientRegistrations.findByRegistrationId(id) != null) out.add(id);
        }
        return out;
    }

    /**
     * Cross-origin policy (issue #17). Default is locked: no origins allowed —
     * the bundled UI is same-origin and needs no CORS. Operators serving a UI
     * from another origin list it in {@code agent.cors.allowed-origins}
     * (env {@code AGENT_CORS_ALLOWED_ORIGINS}), which grants it WITH
     * credentials. The single entry "*" allows any origin but with credentials
     * disabled — reflecting arbitrary origins while sending
     * Access-Control-Allow-Credentials lets any website ride a logged-in
     * browser session, so that combination is never produced.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = props.getCors().getAllowedOrigins().stream()
                .filter(o -> o != null && !o.isBlank())
                .map(String::trim)
                .toList();

        CorsConfiguration cfg = new CorsConfiguration();
        if (origins.contains("*")) {
            cfg.setAllowedOrigins(List.of(CorsConfiguration.ALL));
            cfg.setAllowCredentials(false);
        } else if (!origins.isEmpty()) {
            cfg.setAllowedOrigins(origins);
            cfg.setAllowCredentials(true);
        }
        // else: no allowed origins — every cross-origin request is denied.
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("Content-Type", "Cache-Control", "X-Request-Id"));

        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
