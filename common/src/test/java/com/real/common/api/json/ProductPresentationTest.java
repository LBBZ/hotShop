package com.real.common.api.json;

import com.real.common.api.dto.ProductImage;
import com.real.common.api.dto.ProductPresentation;
import com.real.common.api.dto.ProductSpecification;
import com.real.common.api.dto.ProductWriteRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductPresentationTest {
    @ParameterizedTest
    @ValueSource(strings = {"/media/products/radio.webp", "https://cdn.example.com/radio.jpg?v=2"})
    void acceptsCatalogPathsAndHttpsImages(String url) {
        assertValid(url, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "//example.com/a.png", "http://example.com/a.png",
            "/media/products/../secret.png", "data:image/png;base64,AAAA", "https://user:pass@example.com/a"})
    void rejectsUnsafeOrUnapprovedImageLocations(String url) {
        assertValid(url, false);
    }

    private void assertValid(String url, boolean expected) {
        var media = new ProductPresentation(List.of(new ProductImage(url, "Radio front view")),
                List.of(new ProductSpecification("Color", "Amber")), "AI-generated demo image");
        var request = new ProductWriteRequest("Radio", new BigDecimal("299.00"), 5, "Audio", "Demo", media);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request).isEmpty()).isEqualTo(expected);
        }
    }

    @Test
    void roundTripsPresentationAndKeepsLegacyRequestsOptional() {
        var mapper = JsonMapper.builder().build();
        var media = new ProductPresentation(List.of(new ProductImage("/media/products/radio.webp", "Radio")),
                List.of(new ProductSpecification("Color", "Amber")), "Demo");
        assertThat(mapper.readValue(mapper.writeValueAsString(media), ProductPresentation.class)).isEqualTo(media);
        var legacy = mapper.readValue("""
                {"name":"Radio","price":"299.00","stock":5,"category":"Audio"}
                """, ProductWriteRequest.class);
        assertThat(legacy.presentation()).isNull();
        assertThat(new ProductPresentation(null, null, null).images()).isEmpty();
    }
}
