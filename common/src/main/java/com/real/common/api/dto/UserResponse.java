package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.real.common.enums.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserResponse(
        @JsonSerialize(using = ToStringSerializer.class)
        @Schema(type = "string", pattern = "^[1-9][0-9]*$", example = "123",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long userId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String username,
        String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Role role,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant createdAt
) {
}
