package com.real.infrastructure.Swagger;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "HotShop API",
                version = "1.0.0",
                description = "Runtime-generated HTTP contract for HotShop"
        )
)
public class OpenApiConfig {
    private static final String POSITIVE_LONG_ID_PATTERN = "^[1-9][0-9]{0,18}$";
    private static final String ORDER_ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$";

    @Bean
    public OpenApiCustomizer contractCustomizer() {
        return openApi -> {
            Components components = openApi.getComponents() == null ? new Components() : openApi.getComponents();
            openApi.setComponents(components);
            openApi.setServers(List.of(new Server().url("/").description("Same-origin API")));
            addSecurityScheme(components);
            addProblemSchemas(components);
            addReusableHeadersAndParameters(components);
            addProblemResponses(components);
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(pathItem ->
                    pathItem.readOperations().forEach(operation -> {
                        addParameterIfMissing(operation, "#/components/parameters/RequestId");
                        addParameterIfMissing(operation, "#/components/parameters/TraceParent");
                        normalizeUrlIdParameters(operation);
                        operation.getResponses().putIfAbsent("400", refResponse("BadRequest"));
                        operation.getResponses().putIfAbsent("401", refResponse("Unauthorized"));
                        operation.getResponses().putIfAbsent("403", refResponse("Forbidden"));
                        operation.getResponses().putIfAbsent("404", refResponse("NotFound"));
                        operation.getResponses().putIfAbsent("405", refResponse("MethodNotAllowed"));
                        operation.getResponses().putIfAbsent("406", refResponse("NotAcceptable"));
                        operation.getResponses().putIfAbsent("409", refResponse("Conflict"));
                        operation.getResponses().putIfAbsent("429", refResponse("RateLimited"));
                        operation.getResponses().putIfAbsent("415", refResponse("UnsupportedMediaType"));
                        operation.getResponses().putIfAbsent("503", refResponse("ServiceUnavailable"));
                        operation.getResponses().putIfAbsent("500", refResponse("InternalError"));
                        operation.getResponses().values().forEach(response -> {
                            if (response.getHeaders() == null) {
                                response.setHeaders(new java.util.LinkedHashMap<>());
                            }
                            response.getHeaders().putIfAbsent(
                                    "X-Request-Id",
                                    new Header().$ref("#/components/headers/RequestId")
                            );
                            response.getHeaders().putIfAbsent(
                                    "X-Trace-Id",
                                    new Header().$ref("#/components/headers/TraceId")
                            );
                        });
                    })
            );
        };
    }

    @Bean
    public GroupedOpenApi publicApi(OpenApiCustomizer contractCustomizer) {
        return GroupedOpenApi.builder()
                .group("public")
                .displayName("Public API v1")
                .pathsToMatch(
                        "/api/v1/products/**",
                        "/api/v1/auth/register",
                        "/api/v1/auth/login"
                )
                .addOpenApiCustomizer(contractCustomizer)
                .build();
    }

    @Bean
    public GroupedOpenApi userApi(OpenApiCustomizer contractCustomizer) {
        return GroupedOpenApi.builder()
                .group("user")
                .displayName("User API v1")
                .pathsToMatch(
                        "/api/v1/users/**",
                        "/api/v1/orders/**",
                        "/api/v1/flash-sales/**",
                        "/api/v1/auth/logout",
                        "/api/v1/auth/refresh"
                )
                .addOpenApiCustomizer(contractCustomizer)
                .build();
    }

    @Bean
    public GroupedOpenApi adminApi(OpenApiCustomizer contractCustomizer) {
        return GroupedOpenApi.builder()
                .group("admin")
                .displayName("Admin API v1")
                .pathsToMatch("/admin/api/v1/**")
                .addOpenApiCustomizer(contractCustomizer)
                .build();
    }

    @Bean
    public GroupedOpenApi agentBoundaryApi(OpenApiCustomizer contractCustomizer) {
        return GroupedOpenApi.builder()
                .group("agent-boundary")
                .displayName("Reserved Agent API v1 boundary")
                .pathsToMatch("/agent/api/v1/**")
                .addOpenApiCustomizer(contractCustomizer)
                .build();
    }

    private void addSecurityScheme(Components components) {
        components.addSecuritySchemes(
                "bearerAuth",
                new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token. User, Administrator, and future Agent audiences remain distinct.")
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void addProblemSchemas(Components components) {
        Schema violation = new ObjectSchema()
                .addProperty("field", new StringSchema())
                .addProperty("code", new StringSchema())
                .addProperty("message", new StringSchema())
                .required(List.of("field", "code", "message"));
        components.addSchemas("ApiViolation", violation);

        Schema problem = new ObjectSchema()
                .addProperty("type", new StringSchema().format("uri"))
                .addProperty("title", new StringSchema())
                .addProperty("status", new IntegerSchema().format("int32"))
                .addProperty("detail", new StringSchema())
                .addProperty("instance", new StringSchema().format("uri"))
                .addProperty("code", new StringSchema())
                .addProperty(
                        "requestId",
                        new StringSchema().pattern("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")
                )
                .addProperty("traceId", new StringSchema().pattern("^[0-9a-f]{32}$"))
                .addProperty(
                        "violations",
                        new ArraySchema().items(new Schema<>().$ref("#/components/schemas/ApiViolation"))
                )
                .required(List.of(
                        "type",
                        "title",
                        "status",
                        "detail",
                        "instance",
                        "code",
                        "requestId",
                        "traceId"
                ));
        components.addSchemas("ApiProblem", problem);
    }

    private void addReusableHeadersAndParameters(Components components) {
        components.addParameters(
                "RequestId",
                new Parameter()
                        .name("X-Request-Id")
                        .in("header")
                        .required(false)
                        .description("Caller-supplied correlation ID. Invalid values are replaced by the server.")
                        .schema(new StringSchema().pattern("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$").maxLength(64))
        );
        components.addParameters(
                "TraceParent",
                new Parameter()
                        .name("traceparent")
                        .in("header")
                        .required(false)
                        .description("W3C trace context. Its trace ID is distinct from X-Request-Id.")
                        .schema(new StringSchema().pattern(
                                "^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$"
                        ))
        );
        components.addParameters(
                "IdempotencyKey",
                new Parameter()
                        .name("Idempotency-Key")
                        .in("header")
                        .required(true)
                        .description(
                                "Reserved for write operations that persist replay state. "
                                        + "Keys are 16-128 visible ASCII characters and are scoped to actor and operation."
                        )
                        .schema(new StringSchema().pattern("^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$"))
        );
        components.addHeaders(
                "RequestId",
                new Header()
                        .description("Effective request correlation ID")
                        .schema(new StringSchema())
        );
        components.addHeaders(
                "TraceId",
                new Header()
                        .description("Effective distributed trace ID")
                        .schema(new StringSchema().pattern("^[0-9a-f]{32}$"))
        );
        components.addHeaders(
                "IdempotencyReplayed",
                new Header()
                        .description("true when a persisted response is replayed for the same Idempotency-Key and fingerprint")
                        .schema(new Schema<Boolean>().type("boolean"))
        );
    }

    private void addProblemResponses(Components components) {
        Map<String, String> responses = Map.ofEntries(
                Map.entry("BadRequest", "Invalid request"),
                Map.entry("Unauthorized", "Authentication required"),
                Map.entry("Forbidden", "Access denied"),
                Map.entry("NotFound", "Resource not found"),
                Map.entry("MethodNotAllowed", "Request method is not supported"),
                Map.entry("NotAcceptable", "Requested response media type is not available"),
                Map.entry("Conflict", "State or idempotency conflict"),
                Map.entry("RateLimited", "Request rate limit exceeded"),
                Map.entry("UnsupportedMediaType", "Request media type is not supported"),
                Map.entry("ServiceUnavailable", "Authentication dependency is unavailable"),
                Map.entry("InternalError", "Unexpected server error")
        );
        responses.forEach((name, description) -> components.addResponses(
                name,
                new ApiResponse()
                        .description(description)
                        .content(new io.swagger.v3.oas.models.media.Content().addMediaType(
                                MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                new io.swagger.v3.oas.models.media.MediaType()
                                        .schema(new Schema<>().$ref("#/components/schemas/ApiProblem"))
                        ))
        ));
    }

    private ApiResponse refResponse(String name) {
        return new ApiResponse().$ref("#/components/responses/" + name);
    }

    private void addParameterIfMissing(io.swagger.v3.oas.models.Operation operation, String reference) {
        boolean exists = operation.getParameters() != null
                && operation.getParameters().stream().anyMatch(parameter -> reference.equals(parameter.get$ref()));
        if (!exists) {
            operation.addParametersItem(new Parameter().$ref(reference));
        }
    }

    private void normalizeUrlIdParameters(io.swagger.v3.oas.models.Operation operation) {
        if (operation.getParameters() == null) {
            return;
        }
        operation.getParameters().stream()
                .filter(parameter -> parameter.get$ref() == null)
                .filter(parameter -> "path".equals(parameter.getIn()) || "query".equals(parameter.getIn()))
                .forEach(parameter -> {
                    if ("productId".equals(parameter.getName())
                            || "userId".equals(parameter.getName())
                            || "activityId".equals(parameter.getName())) {
                        parameter.setSchema(new StringSchema()
                                .pattern(POSITIVE_LONG_ID_PATTERN)
                                .minLength(1)
                                .maxLength(19));
                    } else if ("orderId".equals(parameter.getName()) && "path".equals(parameter.getIn())) {
                        parameter.setSchema(new StringSchema()
                                .pattern(ORDER_ID_PATTERN)
                                .minLength(1)
                                .maxLength(64));
                    }
                });
    }
}
