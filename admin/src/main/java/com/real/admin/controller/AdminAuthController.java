package com.real.admin.controller;

import com.real.common.api.dto.AuthTokenResponse;
import com.real.common.api.dto.LoginRequestDto;
import com.real.common.api.dto.MessageResponse;
import com.real.common.enums.Role;
import com.real.common.enums.TokenType;
import com.real.domain.entity.User;
import com.real.domain.service.UserService;
import com.real.security.entity.CustomUserDetails;
import com.real.security.service.TokenBlacklistService;
import com.real.security.util.JwtTokenUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

@RestController
@Tag(name = "Admin authentication", description = "Administrator authentication")
@RequestMapping("/admin/api/v1/auth")
public class AdminAuthController {
    private final AuthenticationManager authenticationManager;
    private final JwtTokenUtil jwtTokenUtil;
    private final UserService userService;
    private final TokenBlacklistService tokenBlacklistService;

    public AdminAuthController(
            AuthenticationManager authenticationManager,
            JwtTokenUtil jwtTokenUtil,
            UserService userService,
            TokenBlacklistService tokenBlacklistService
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenUtil = jwtTokenUtil;
        this.userService = userService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Operation(summary = "Sign in as an Administrator")
    @PostMapping("/login")
    public ResponseEntity<AuthTokenResponse> login(@RequestBody @Valid LoginRequestDto request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );
        CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
        User user = userService.getUserByUsername(request.username());
        if (user == null || user.getRole() != Role.ROLE_ADMIN) {
            throw new BadCredentialsException("Invalid credentials");
        }
        HashMap<String, Object> claims = new HashMap<>();
        claims.put("tokenType", TokenType.ACCESS);
        String accessToken = jwtTokenUtil.buildToken(claims, principal.getUsername(), 86400L);
        return ResponseEntity.ok(new AuthTokenResponse(
                user.getRole(),
                user.getUserId(),
                user.getUsername(),
                accessToken,
                jwtTokenUtil.getExpirationDateFromToken(accessToken).toInstant()
        ));
    }

    @Operation(summary = "Sign out as an Administrator", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/logout")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<MessageResponse> logout(HttpServletRequest request) {
        String accessToken = jwtTokenUtil.extractToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (accessToken != null) {
            tokenBlacklistService.addToBlacklist(accessToken);
        }
        return ResponseEntity.ok(new MessageResponse("Signed out"));
    }
}
