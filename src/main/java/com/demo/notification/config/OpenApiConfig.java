package com.demo.notification.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 documentation configuration for the Enterprise Notification Management Service.
 * <p>
 * Provides automated interactive documentation via Swagger UI at {@code /swagger-ui.html}
 * and OpenAPI 3.1 specification at {@code /v3/api-docs}.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Enterprise Notification Management Service API",
                version = "1.0.0",
                description = "Production-grade event-driven notification management service prototype. " +
                        "Features composite multi-key idempotency deduplication, ADR-001 Intelligent Fallback Routing " +
                        "(regulatory overrides, quiet-hours diversion, opt-out diversion), Resilience4j fault tolerance, " +
                        "and end-to-end distributed tracing via X-Correlation-ID.",
                contact = @Contact(
                        name = "Enterprise Notification Service Engineering",
                        email = "support@example.com"
                ),
                license = @License(
                        name = "Apache 2.0",
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                )
        ),
        servers = {
                @Server(url = "/", description = "Current Server Context")
        }
)
public class OpenApiConfig {
}

