package com.real.admin.controller;

import com.real.common.api.ApiException;
import com.real.common.api.dto.AuthTokenResponse;
import com.real.common.api.dto.LoginRequestDto;
import com.real.common.api.dto.MessageResponse;
import com.real.common.enums.Role;
import com.real.domain.entity.User;
import com.real.domain.service.UserService;
import com.real.security.api.RefreshSessionRejectedException;
import com.real.security.entity.CustomUserDetails;
import com.real.security.identity.IdentityType;
import com.real.security.identity.IssuedAccessToken;
import com.real.security.identity.RefreshRotationResult;
import com.real.security.identity.RefreshSessionTokens;
import com.real.security.identity.SessionType;
import com.real.security.service.AuthenticationRateLimiter;
import com.real.security.service.RefreshCookieService;
import com.real.security.service.RefreshSessionService;
import com.real.security.service.SecurityAuditService;
import com.real.security.service.TokenBlacklistService;
import com.real.security.util.JwtTokenUtil;
import io.jsonwebtoken.JwtException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@Tag(name = "Admin authentication", description = "Administrator authentication")
@RequestMapping("/admin/api/v1/auth")
public class AdminAuthController {
    private final AuthenticationManager authenticationManager;
    private final JwtTokenUtil jwtTokenUtil;
    private final UserService userService;
    private final TokenBlacklistService tokenBlacklistService;
    private final AuthenticationRateLimiter rateLimiter;
    private final RefreshSessionService refreshSessionService;
    private final RefreshCookieService cookieService;
    private final SecurityAuditService auditService;

    public AdminAuthController(
            AuthenticationManager authenticationManager,
            JwtTokenUtil jwtTokenUtil,
            UserService userService,
            TokenBlacklistService tokenBlacklistService,
            AuthenticationRateLimiter rateLimiter,
            RefreshSessionService refreshSessionService,
            RefreshCookieService cookieService,
            SecurityAuditService auditService
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenUtil = jwtTokenUtil;
        this.userService = userService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.rateLimiter = rateLimiter;
        this.refreshSessionService = refreshSessionService;
        this.cookieService = cookieService;
        this.auditService = auditService;
    }

    @Operation(summary = "Sign in as an Administrator")
    @PostMapping("/login")
    public ResponseEntity<AuthTokenResponse> login(
            @RequestBody @Valid LoginRequestDto request,
            HttpServletRequest servletRequest
    ) {
        rateLimiter.beforeLogin(SessionType.ADMIN, servletRequest, request.username());
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );
            CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
            User user = userService.getUserByUsername(request.username());
            if (user == null || user.getRole() != Role.ROLE_ADMIN) {
                throw new BadCredentialsException("Invalid credentials");
            }
            IssuedAccessToken accessToken = jwtTokenUtil.issueAdministratorAccess(principal);
            RefreshSessionTokens refresh = refreshSessionService.create(
                    SessionType.ADMIN,
                    principal.getUserId(),
                    servletRequest
            );
            return tokenResponse(
                    principal.getUserId(),
                    principal.getUsername(),
                    accessToken,
                    refresh
            );
        } catch (AuthenticationException exception) {
            auditService.loginFailed(
                    SessionType.ADMIN.name(),
                    AuthenticationRateLimiter.hash(request.username().toLowerCase(Locale.ROOT)),
                    servletRequest
            );
            rateLimiter.recordLoginFailure(SessionType.ADMIN, servletRequest, request.username());
            throw new BadCredentialsException("Invalid credentials");
        }
    }

    @Operation(summary = "Refresh the Administrator access token")
    @PostMapping("/refresh")
    public ResponseEntity<AuthTokenResponse> refresh(
            HttpServletRequest request,
            @CookieValue(name = RefreshCookieService.ADMIN_REFRESH_COOKIE, required = false)
            String refreshToken,
            @CookieValue(name = RefreshCookieService.ADMIN_CSRF_COOKIE, required = false)
            String csrfCookie,
            @RequestHeader(name = RefreshCookieService.CSRF_HEADER, required = false)
            String csrfHeader
    ) {
        rateLimiter.beforeRefresh(SessionType.ADMIN, request);
        cookieService.requireValidCsrf(csrfCookie, csrfHeader);
        RefreshRotationResult result = refreshSessionService.rotate(
                SessionType.ADMIN,
                refreshToken,
                csrfCookie,
                request
        );
        if (result.status() != RefreshRotationResult.Status.ROTATED) {
            throw new RefreshSessionRejectedException(SessionType.ADMIN);
        }
        CustomUserDetails principal = CustomUserDetails.builder()
                .userId(result.userId())
                .username(result.username())
                .password("")
                .authorities(
                        JwtTokenUtil.administratorAuthorities().stream()
                                .map(SimpleGrantedAuthority::new)
                                .toList()
                )
                .identityType(IdentityType.ADMINISTRATOR_ACCESS)
                .build();
        IssuedAccessToken accessToken = jwtTokenUtil.issueAdministratorAccess(principal);
        return tokenResponse(
                result.userId(),
                result.username(),
                accessToken,
                result.tokens()
        );
    }

    @Operation(summary = "Sign out as an Administrator")
    @PostMapping("/logout")
    public ResponseEntity<MessageResponse> logout(
            HttpServletRequest request,
            @CookieValue(name = RefreshCookieService.ADMIN_REFRESH_COOKIE, required = false)
            String refreshToken,
            @CookieValue(name = RefreshCookieService.ADMIN_CSRF_COOKIE, required = false)
            String csrfCookie,
            @RequestHeader(name = RefreshCookieService.CSRF_HEADER, required = false)
            String csrfHeader
    ) {
        if (refreshToken != null) {
            cookieService.requireValidCsrf(csrfCookie, csrfHeader);
            refreshSessionService.logout(SessionType.ADMIN, refreshToken);
        }
        revokePresentedAccessToken(request);
        ResponseEntity.BodyBuilder response = noStore(ResponseEntity.ok());
        cookieService.clearCookies(SessionType.ADMIN)
                .forEach(cookie -> response.header(HttpHeaders.SET_COOKIE, cookie.toString()));
        return response.body(new MessageResponse("Signed out"));
    }

    private ResponseEntity<AuthTokenResponse> tokenResponse(
            long userId,
            String username,
            IssuedAccessToken accessToken,
            RefreshSessionTokens refresh
    ) {
        ResponseCookie refreshCookie = cookieService.refreshCookie(SessionType.ADMIN, refresh.refreshToken());
        ResponseCookie legacyCsrfCookie = cookieService.legacyCsrfCookie(SessionType.ADMIN);
        ResponseCookie csrfCookie = cookieService.csrfCookie(SessionType.ADMIN, refresh.csrfToken());
        return noStore(ResponseEntity.ok())
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshCookie.toString(),
                        csrfCookie.toString(),
                        legacyCsrfCookie.toString()
                )
                .body(new AuthTokenResponse(
                        Role.ROLE_ADMIN,
                        userId,
                        username,
                        accessToken.value(),
                        accessToken.expiresAt()
                ));
    }

    private void revokePresentedAccessToken(HttpServletRequest request) {
        String token = jwtTokenUtil.extractToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            return;
        }
        try {
            var validated = jwtTokenUtil.validate(token, IdentityType.ADMINISTRATOR_ACCESS);
            tokenBlacklistService.revoke(validated.jti(), validated.expiresAt());
        } catch (DataAccessException ex) {
            throw ApiException.serviceUnavailable(
                    "AUTHENTICATION_SERVICE_UNAVAILABLE",
                    "Authentication dependency unavailable"
            );
        } catch (JwtException | IllegalArgumentException ignored) {
            // Logout remains idempotent and the Refresh Session is still revoked.
        }
    }

    private ResponseEntity.BodyBuilder noStore(ResponseEntity.BodyBuilder builder) {
        return builder.cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache");
    }
}
