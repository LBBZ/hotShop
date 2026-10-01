package com.real.infrastructure.Swagger;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiModelContractTest {
    private ModelConverters converters() {
        ModelConverters converters = new ModelConverters(true);
        converters.addConverter(new OpenApiConfig().runtimeJsonModelConverter());
        return converters;
    }

    @Test
    void jacksonTreeRemainsAnyJsonInsteadOfExposingImplementationProperties() throws Exception {
        ResolvedSchema result = converters().resolveAsResolvedSchema(
                new AnnotatedType(JsonValueResponse.class).resolveAsRef(true));
        Schema<?> response = result.referencedSchemas.get("JsonValueResponse");
        Schema<?> value = response.getProperties().get("proposedValue");
        assertThat(value.get$ref()).isEqualTo("#/components/schemas/JsonNode");
        assertThat(Json31.mapper().writeValueAsString(result.referencedSchemas.get("JsonNode")))
                .isEqualTo("{}");
        assertThat(result.referencedSchemas).containsOnlyKeys("JsonValueResponse", "JsonNode");
    }

    @Test
    void emitterTimeoutRetainsNullableInt64UnderOpenApi31() throws Exception {
        ResolvedSchema result = converters().resolveAsResolvedSchema(
                new AnnotatedType(SseEmitter.class).resolveAsRef(true));
        assertThat(result.schema.get$ref()).isEqualTo("#/components/schemas/SseEmitter");
        assertThat(Json31.mapper().readTree(Json31.mapper().writeValueAsString(
                result.referencedSchemas.get("SseEmitter"))))
                .isEqualTo(Json31.mapper().readTree("""
                        {"type":"object","properties":{"timeout":{"type":["integer","null"],"format":"int64"}}}
                        """));
    }

    @Test
    void otherModelsContinueUsingNormalSchemaResolution() {
        ResolvedSchema result = converters().resolveAsResolvedSchema(
                new AnnotatedType(OrdinaryResponse.class).resolveAsRef(true));
        Schema<?> response = result.referencedSchemas.get("OrdinaryResponse");
        assertThat(response.getProperties().get("name").getTypes()).containsExactly("string");
        assertThat(response.getProperties().get("count").getTypes()).containsExactly("integer");
        assertThat(response.getProperties().get("count").getFormat()).isEqualTo("int32");
        assertThat(result.referencedSchemas).containsOnlyKeys("OrdinaryResponse");
    }

    public record JsonValueResponse(JsonNode proposedValue) { }
    public record OrdinaryResponse(String name, Integer count) { }
}
