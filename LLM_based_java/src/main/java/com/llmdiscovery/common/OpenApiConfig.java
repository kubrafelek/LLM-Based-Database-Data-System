package com.llmdiscovery.common;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the "bearer" scheme the controllers reference with @SecurityRequirement, so Swagger UI
 * (/docs) shows the Authorize button and sends "Authorization: Bearer <token>" on protected calls.
 */
@Configuration
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {}
