package com.real.portal.controller;

import com.real.common.api.ApiException;
import com.real.common.api.dto.AuthTokenResponse;
import com.real.common.api.dto.LoginRequestDto;
import com.real.common.api.dto.MessageResponse;
import com.real.common.api.dto.RegisterRequestDto;
import com.real.common.enums.Role;
import com.real.common.enums.TokenType;
import com.real.domain.entity.User;
import com.real.domain.service.UserService;
import com.real.security.entity.CustomUserDetails;
import com.real.security.service.TokenBlacklistService;
import com.real.security.util.JwtTokenUtil;
import io.jsonwebtoken.JwtException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

@RestController
@Tag(name = "Public authentication", description = "User registration and token lifecycle")
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final JwtTokenUtil jwtTokenUtil;
    private final UserService userService;
    private final UserDetailsService userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;
    private final PasswordEncoder passwordEncoder;

    public AuthController(
            AuthenticationManager authenticationManager,
            JwtTokenUtil jwtTokenUtil,
            UserDetailsService userDetailsService,
            UserService userService,
            TokenBlacklistService tokenBlacklistService,
            PasswordEncoder passwordEncoder
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenUtil = jwtTokenUtil;
        this.userService = userService;
        this.userDetailsService = userDetailsService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.passwordEncoder = passwordEncoder;
    }

    @Operation(summary = "Register a User")
    @PostMapping("/register")
    public ResponseEntity<MessageResponse> register(@RequestBody @Valid RegisterRequestDto request) {
        if (userService.userExistsByUsername(request.username())) {
            throw ApiException.conflict("USERNAME_CONFLICT", "Username is already registered");
        }
        if (userService.userExistsByEmail(request.email())) {
            throw ApiException.conflict("EMAIL_CONFLICT", "Email is already registered");
        }
        User user = new User();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setEmail(request.email());
        user.setRole(Role.ROLE_USER);
        userService.register(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(new MessageResponse("User registered"));
    }

    @Operation(summary = "Sign in as a User")
    @PostMapping("/login")
    public ResponseEntity<AuthTokenResponse> login(@RequestBody @Valid LoginRequestDto request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );
        CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
        User user = userService.getUserByUsername(request.username());
        if (user == null || user.getRole() != Role.ROLE_USER) {
            throw new BadCredentialsException("Invalid credentials");
        }
        String accessToken = jwtTokenUtil.generateToken(principal, TokenType.ACCESS, null);
        String refreshToken = jwtTokenUtil.generateToken(principal, TokenType.REFRESH, null);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, createRefreshTokenCookie(refreshToken).toString())
                .body(toTokenResponse(user, accessToken));
    }

    @Operation(summary = "Sign out", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/logout")
    @PreAuthorize("hasAuthority('ROLE_USER')")
    public ResponseEntity<MessageResponse> logout(
            HttpServletRequest request,
            @CookieValue(name = "refresh_token", required = false) String refreshToken
    ) {
        String accessToken = jwtTokenUtil.extractToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (accessToken != null) {
            tokenBlacklistService.addToBlacklist(accessToken);
        }
        if (refreshToken != null) {
            tokenBlacklistService.addToBlacklist(refreshToken);
        }
        return ResponseEntity.ok(new MessageResponse("Signed out"));
    }

    @Operation(summary = "Refresh the User access token", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/refresh")
    public ResponseEntity<AuthTokenResponse> refreshToken(
            HttpServletRequest request,
            @CookieValue(name = "refresh_token", required = false) String refreshToken
    ) {
        try {
            if (refreshToken == null || !jwtTokenUtil.validateToken(refreshToken, TokenType.REFRESH)) {
                throw new BadCredentialsException("Invalid refresh token");
            }
            String accessToken = jwtTokenUtil.extractToken(request.getHeader(HttpHeaders.AUTHORIZATION));
            if (accessToken == null) {
                throw new BadCredentialsException("Missing access token");
            }
            String username = jwtTokenUtil.getUsernameFromToken(refreshToken);
            User user = userService.getUserByUsername(username);
            if (user == null || user.getRole() != Role.ROLE_USER) {
                throw new BadCredentialsException("Invalid refresh token");
            }
            if (jwtTokenUtil.validateToken(accessToken, TokenType.ACCESS)) {
                return ResponseEntity.ok(toTokenResponse(user, accessToken));
            }
            String newAccessToken = jwtTokenUtil.generateToken(
                    (CustomUserDetails) userDetailsService.loadUserByUsername(username),
                    TokenType.ACCESS,
                    null
            );
            return ResponseEntity.ok(toTokenResponse(user, newAccessToken));
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BadCredentialsException("Invalid token");
        }
    }

    private AuthTokenResponse toTokenResponse(User user, String accessToken) {
        return new AuthTokenResponse(
                user.getRole(),
                user.getUserId(),
                user.getUsername(),
                accessToken,
                jwtTokenUtil.getExpirationDateFromToken(accessToken).toInstant()
        );
    }

    private ResponseCookie createRefreshTokenCookie(String token) {
        return ResponseCookie.from("refresh_token", token)
                .httpOnly(true)
                .secure(false)
                .path("/api/v1/auth/refresh")
                .maxAge(Duration.ofSeconds(jwtTokenUtil.getRefreshExpiration()))
                .sameSite("Strict")
                .build();
    }
}
