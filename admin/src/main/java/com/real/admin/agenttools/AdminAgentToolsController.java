package com.real.admin.agenttools;

import com.real.security.entity.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(
        name = "Admin Agent tools",
        description = "Fixed allowlist of low-risk, audited Administrator Agent tools"
)
@RequestMapping("/admin/api/v1/agent-tools")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class AdminAgentToolsController {
    private final AdminAgentToolService service;

    public AdminAgentToolsController(AdminAgentToolService service) {
        this.service = service;
    }

    @GetMapping("/statistics")
    @Operation(summary = "Read low-risk aggregate statistics")
    public AdminAgentStatisticsResponse statistics(
            @AuthenticationPrincipal CustomUserDetails administrator,
            HttpServletRequest request
    ) {
        return service.statistics(
                administrator.getUserId(),
                !request.getParameterMap().isEmpty(),
                request
        );
    }

    @GetMapping("/anomalies")
    @Operation(summary = "Read a low-risk anomaly summary without raw errors or resource identifiers")
    public AdminAgentAnomalySummaryResponse anomalies(
            @AuthenticationPrincipal CustomUserDetails administrator,
            HttpServletRequest request
    ) {
        return service.anomalies(
                administrator.getUserId(),
                !request.getParameterMap().isEmpty(),
                request
        );
    }

    @PostMapping("/configuration-drafts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a low-risk configuration draft without applying it")
    public AgentConfigurationDraftResponse createConfigurationDraft(
            @RequestBody(required = false) String body,
            @AuthenticationPrincipal CustomUserDetails administrator,
            HttpServletRequest request
    ) {
        return service.createConfigurationDraft(
                administrator.getUserId(),
                body,
                !request.getParameterMap().isEmpty(),
                request
        );
    }
}
