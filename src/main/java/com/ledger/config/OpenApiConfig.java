package com.ledger.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document is generated from the controller and DTO annotations --
 * there is no hand-maintained spec file to drift away from the code.
 * Served at /v3/api-docs, browsable at /swagger-ui.html.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ledgerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Double-entry ledger")
                .version("0.1.0")
                .description("""
                        Append-only double-entry ledger.

                        Money is always an integer number of minor units \
                        (`amountMinor`). There is no decimal or floating-point \
                        money anywhere in this API.

                        Postings are signed: positive debits an account, negative \
                        credits it, and they must sum to zero per currency. That \
                        invariant is enforced by a deferred constraint trigger in \
                        Postgres, not only by this service.

                        Writes require an `Idempotency-Key` header. Errors are \
                        RFC 7807 `application/problem+json`.""")
                .contact(new Contact().name("ledger-service"))
                .license(new License().name("MIT")));
    }
}
