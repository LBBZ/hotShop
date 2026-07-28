package com.real.common.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record AgentTokenExchangeRequest(
        @NotBlank
        @Size(max = 16_384)
        @Schema(description = "User Access Token presented as the delegated subject credential")
        String subjectToken,
        @NotBlank
        @Size(max = 16_384)
        @Schema(description = "Short-lived Agent Service client assertion")
        String clientAssertion,
        @NotEmpty
        @Size(max = 8)
        Set<
                @Pattern(regexp = "^[a-z][a-z0-9]*(?::[a-z][a-z0-9]*){1,3}$")
                @Size(max = 64)
                String
                > scopes
) {
}
