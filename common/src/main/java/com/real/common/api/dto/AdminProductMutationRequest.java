package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.real.common.api.json.DecimalStringDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record AdminProductMutationRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2)
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        @Schema(type = "string", pattern = "^(0|[1-9][0-9]*)\\.[0-9]{2}$", example = "6999.00")
        BigDecimal price,
        @NotNull @PositiveOrZero Integer stock,
        @NotBlank @Size(max = 100) String category,
        @Size(max = 4000) String description,
        @NotBlank @Size(min = 3, max = 256)
        @Pattern(regexp = "^(?=(?:.*\\S){3,})[^\\r\\n]*$")
        String reason
) {
    public ProductWriteRequest product() {
        return new ProductWriteRequest(name, price, stock, category, description);
    }
}
