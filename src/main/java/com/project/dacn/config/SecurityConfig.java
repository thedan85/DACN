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
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // CORS preflight never carries credentials.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Public unless a rule below says otherwise.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/favicon.ico", "/static/**", "/css/**", "/js/**",
                                "/images/**", "/webjars/**").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/api/v1/health/**").permitAll()
                        // Payment providers must call these without a token; they verify signatures.
                        .requestMatchers("/api/v1/webhooks/**").permitAll()

                        // Session endpoints.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/api/v1/auth/**").authenticated()

                        // Storefront reads.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/products/**", "/api/v1/variants/**", "/api/v1/categories/**",
                                "/api/v1/attributes/**", "/api/v1/attribute-values/**",
                                "/api/v1/product-attributes/**", "/api/v1/variant-attributes/**",
                                "/api/v1/media/**").permitAll()

                        // Writes are checked here as well as with @PreAuthorize so a caller
                        // without the permission is rejected before MVC validates the body.
                        .requestMatchers(HttpMethod.POST, "/api/v1/products/**").hasAuthority("product:create")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/products/**").hasAuthority("product:update")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/products/**").hasAuthority("product:update")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/products/**").hasAuthority("product:delete")

                        .requestMatchers(HttpMethod.POST, "/api/v1/variants/**").hasAuthority("variant:create")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/variants/**").hasAuthority("variant:update")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/variants/**").hasAuthority("variant:update")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/variants/**").hasAuthority("variant:delete")

                        .requestMatchers(HttpMethod.POST, "/api/v1/categories/**").hasAuthority("category:create")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/categories/**").hasAuthority("category:update")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/categories/**").hasAuthority("category:update")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/categories/**").hasAuthority("category:delete")

                        .requestMatchers(HttpMethod.POST, "/api/v1/attributes/**",
                                "/api/v1/attribute-values/**", "/api/v1/product-attributes/**")
                        .hasAuthority("attribute:create")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/attributes/**",
                                "/api/v1/attribute-values/**")
                        .hasAuthority("attribute:update")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/attributes/**",
                                "/api/v1/attribute-values/**")
                        .hasAuthority("attribute:update")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/attributes/**",
                                "/api/v1/attribute-values/**", "/api/v1/product-attributes/**")
                        .hasAuthority("attribute:delete")

                        .requestMatchers(HttpMethod.POST, "/api/v1/variant-attributes/**")
                        .hasAuthority("variant:create")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/variant-attributes/**")
                        .hasAuthority("variant:delete")

                        .requestMatchers(HttpMethod.POST, "/api/v1/media/**", "/api/v1/uploads/**")
                        .hasAuthority("media:create")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/media/**").hasAuthority("media:update")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/media/**").hasAuthority("media:update")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/media/**").hasAuthority("media:delete")

                        .requestMatchers(HttpMethod.GET, "/api/v1/staff/orders/**").hasAuthority("order:read")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/staff/orders/**").hasAuthority("order:update")

                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                );
        return http.build();
    }
}
