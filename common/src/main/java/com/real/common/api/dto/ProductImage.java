package com.real.common.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProductImage(
        @NotBlank @Size(max = 2048)
        @Pattern(regexp = "^(?:/media/products/[A-Za-z0-9/_-]+\\.(?:webp|png|jpe?g)|https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:/[^\\s\\\\<>\"#]*)?)$")
        String url,
        @NotBlank @Size(max = 160) String alt
) { }
