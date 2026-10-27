package com.project.dacn.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private final boolean demoEnabled;

    public OpenApiConfig(@Value("${app.openapi.demo-enabled:false}") boolean demoEnabled) {
        this.demoEnabled = demoEnabled;
    }

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("DACN API")
                        .version("v1")
                        .description(description()))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    private String description() {
        String base = """
                Backend API for the DACN storefront and catalog.

                **Authenticate**
                1. Call `POST /api/v1/auth/login`.
                2. Copy `data.accessToken` from the response.
                3. Click **Authorize** and paste the token.
                """;
        if (!demoEnabled) {
            return base;
        }
        return base + """

                **Dev demo accounts** — password `Swagger123!`

                | Role | Email |
                | --- | --- |
                | Admin | `swagger.admin@example.com` |
                | Employee | `swagger.employee@example.com` |
                | Customer | `swagger.customer@example.com` |

                The customer already has a cart with `ember-tetra-default` and
                `neon-tetra-default`, plus a pending order `ORD_SWAGGER_DEMO` for
                the staff order endpoints. These seeded accounts exist only when
                the `dev` profile loads `classpath:db/dev`; never use them outside
                local development.
                """;
    }
}
