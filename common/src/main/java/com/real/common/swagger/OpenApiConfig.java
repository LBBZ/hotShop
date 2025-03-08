package com.real.common.swagger;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "HotShop API 文档",
                version = "1.0",
                description = "电商平台接口文档"
        )
)
public class OpenApiConfig {

    // 全局安全配置 JWT
    @Bean
    public OpenApiCustomizer securityOpenApiCustomizer() {
        return openApi -> openApi.getComponents()
                .addSecuritySchemes("JWT", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"));
    }
}