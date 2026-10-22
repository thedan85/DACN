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

    private static final String[] PUBLIC_GETS = {
            "/api/v1/products/**",
            "/api/v1/categories/**",
            "/api/v1/variants/**",
            "/api/v1/attributes/**",
            "/api/v1/attribute-values/**",
            "/api/v1/product-attributes/**",
            "/api/v1/variant-attributes/**",
            "/api/v1/media/**"
    };

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
                        // /me requires authentication (must come BEFORE /auth/** permitAll)
                        .requestMatchers("/api/v1/auth/me").authenticated()
                        // Auth endpoints public
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Swagger/actuator public
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Payment provider callbacks: public, signature verified in each handler
                        .requestMatchers("/api/v1/webhooks/**", "/api/webhooks/**").permitAll()
                        // Storefront catalog reads are public; writes fall through to
                        // .authenticated() and then to @PreAuthorize on the handler.
                        .requestMatchers(HttpMethod.GET, PUBLIC_GETS).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                );
        return http.build();
    }
}
