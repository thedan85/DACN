package com.project.dacn.config;

import com.project.dacn.security.AuthenticationEntryPoint;
import com.project.dacn.security.CustomAccessDeniedHandler;
import com.project.dacn.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // CORS preflight must never require a token.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Auth: only /me requires a token; login/register/refresh/logout are public.
                        .requestMatchers("/api/v1/auth/me").authenticated()
                        .requestMatchers("/api/v1/auth/**").permitAll()

                        // VNPay calls IPN/return server-to-server; authenticity is enforced by
                        // the HMAC-SHA512 signature and amount check inside the webhook.
                        .requestMatchers("/api/v1/webhooks/**").permitAll()

                        // Ops/docs endpoints.
                        .requestMatchers("/api/v1/health", "/api/v1/health/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()

                        // Public catalog reads.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/products", "/api/v1/products/**",
                                "/api/v1/categories", "/api/v1/categories/**",
                                "/api/v1/variants", "/api/v1/variants/**",
                                "/api/v1/attributes", "/api/v1/attributes/**",
                                "/api/v1/attribute-values", "/api/v1/attribute-values/**",
                                "/api/v1/media", "/api/v1/media/**",
                                "/api/v1/product-attributes", "/api/v1/product-attributes/**",
                                "/api/v1/variant-attributes", "/api/v1/variant-attributes/**"
                        ).permitAll()

                        // Nested catalog writes (must be declared before the generic per-resource rules).
                        .requestMatchers(HttpMethod.POST, "/api/v1/products/*/media")
                        .hasAuthority("media:create")
                        .requestMatchers(HttpMethod.POST, "/api/v1/products/*/attributes")
                        .hasAuthority("product:update")
                        .requestMatchers(HttpMethod.POST, "/api/v1/variants/*/attributes")
                        .hasAuthority("variant:update")
                        .requestMatchers(HttpMethod.POST, "/api/v1/uploads/**")
                        .hasAuthority("media:create")

                        // Catalog writes require the matching RBAC permission.
                        .requestMatchers(HttpMethod.POST, "/api/v1/products", "/api/v1/products/**")
                        .hasAuthority("product:create")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/products/**")
                        .hasAuthority("product:update")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/products/**")
                        .hasAuthority("product:update")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/products/**")
                        .hasAuthority("product:delete")

                        .requestMatchers(HttpMethod.POST, "/api/v1/categories", "/api/v1/categories/**")
                        .hasAuthority("category:create")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/categories/**")
                        .hasAuthority("category:update")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/categories/**")
                        .hasAuthority("category:update")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/categories/**")
                        .hasAuthority("category:delete")

                        .requestMatchers(HttpMethod.POST, "/api/v1/variants", "/api/v1/variants/**")
                        .hasAuthority("variant:create")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/variants/**")
                        .hasAuthority("variant:update")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/variants/**")
                        .hasAuthority("variant:update")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/variants/**")
                        .hasAuthority("variant:delete")

                        .requestMatchers(HttpMethod.POST, "/api/v1/attributes", "/api/v1/attributes/**")
                        .hasAuthority("attribute:create")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/attributes/**")
                        .hasAuthority("attribute:update")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/attributes/**")
                        .hasAuthority("attribute:update")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/attributes/**")
                        .hasAuthority("attribute:delete")

                        .requestMatchers(HttpMethod.POST, "/api/v1/attribute-values", "/api/v1/attribute-values/**")
                        .hasAuthority("attribute-value:create")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/attribute-values/**")
                        .hasAuthority("attribute-value:update")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/attribute-values/**")
                        .hasAuthority("attribute-value:update")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/attribute-values/**")
                        .hasAuthority("attribute-value:delete")

                        .requestMatchers(HttpMethod.POST, "/api/v1/media", "/api/v1/media/**")
                        .hasAuthority("media:create")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/media/**")
                        .hasAuthority("media:update")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/media/**")
                        .hasAuthority("media:update")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/media/**")
                        .hasAuthority("media:delete")

                        .requestMatchers(HttpMethod.POST, "/api/v1/product-attributes", "/api/v1/product-attributes/**")
                        .hasAuthority("product:update")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/product-attributes/**")
                        .hasAuthority("product:update")

                        .requestMatchers(HttpMethod.POST, "/api/v1/variant-attributes", "/api/v1/variant-attributes/**")
                        .hasAuthority("variant:update")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/variant-attributes/**")
                        .hasAuthority("variant:update")

                        // Shop owner/employee order management. Permissions are
                        // seeded in V17; customers must not reach these routes.
                        .requestMatchers(HttpMethod.GET, "/api/v1/staff/orders", "/api/v1/staff/orders/**")
                        .hasAuthority("order:read")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/staff/orders/**")
                        .hasAuthority("order:update")

                        // Customer-scoped endpoints: identity comes from the JWT.
                        .requestMatchers("/api/v1/cart", "/api/v1/cart/**").authenticated()
                        .requestMatchers("/api/v1/orders", "/api/v1/orders/**").authenticated()
                        .requestMatchers("/api/v1/payments", "/api/v1/payments/**").authenticated()

                        // Anything else is private by default.
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                );
        return http.build();
    }
}
