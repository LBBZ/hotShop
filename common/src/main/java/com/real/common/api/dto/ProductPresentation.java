package com.real.common.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProductPresentation(
        @Size(max = 8) List<@NotNull @Valid ProductImage> images,
        @Size(max = 12) List<@NotNull @Valid ProductSpecification> specifications,
        @Size(max = 160) String imageNote
) {
    public ProductPresentation {
        images = images == null ? List.of() : List.copyOf(images);
        specifications = specifications == null ? List.of() : List.copyOf(specifications);
    }
}
