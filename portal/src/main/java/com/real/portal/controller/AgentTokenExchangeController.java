package com.real.portal.controller;

import com.real.common.api.dto.AgentTokenExchangeRequest;
import com.real.common.api.dto.AgentTokenExchangeResponse;
import com.real.security.service.AgentTokenExchangeService;
import com.real.security.service.AuthenticationRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Agent authentication boundary", description = "Service-authenticated Agent delegation exchange")
@RequestMapping("/agent/api/v1/auth")
public class AgentTokenExchangeController {
    private final AgentTokenExchangeService exchangeService;
    private final AuthenticationRateLimiter rateLimiter;

    public AgentTokenExchangeController(
            AgentTokenExchangeService exchangeService,
            AuthenticationRateLimiter rateLimiter
    ) {
        this.exchangeService = exchangeService;
        this.rateLimiter = rateLimiter;
    }

    @Operation(summary = "Exchange Agent Service and User credentials for Agent Delegation")
    @PostMapping("/token-exchange")
    public ResponseEntity<AgentTokenExchangeResponse> exchange(
            @RequestBody @Valid AgentTokenExchangeRequest request,
            HttpServletRequest servletRequest
    ) {
        rateLimiter.beforeAgentExchange(servletRequest);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(exchangeService.exchange(request, servletRequest));
    }
}
