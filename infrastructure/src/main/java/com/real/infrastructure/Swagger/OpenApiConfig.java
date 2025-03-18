package com.real.infrastructure.Swagger;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
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

    // 在 SwaggerConfig.java 中添加以下方法：
    @Bean
    public GroupedOpenApi portalApi() {
        return GroupedOpenApi.builder()
                .group("前台接口和公开接口")
                .pathsToMatch("/portal/**")
                .build();
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("后台接口")
                .pathsToMatch("/admin/**")
                .build();
    }
}