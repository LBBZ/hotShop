package com.real.security.util;

import com.real.common.api.ApiException;
import com.real.common.api.ProblemResponseWriter;
import com.real.security.api.ProblemAuthenticationEntryPoint;
import com.real.security.entity.CustomUserDetails;
import com.real.security.identity.IdentityType;
import com.real.security.identity.ValidatedToken;
import com.real.security.service.AgentTokenExchangeService;
import com.real.security.service.TokenBlacklistService;
import io.jsonwebtoken.JwtException;
import jakarta.annotation.Nonnull;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class JwtFilter extends OncePerRequestFilter {
    private final JwtTokenUtil jwtTokenUtil;
    private final TokenBlacklistService tokenBlacklistService;
    private final ProblemAuthenticationEntryPoint authenticationEntryPoint;
    private final ProblemResponseWriter problemResponseWriter;

    public JwtFilter(
            JwtTokenUtil jwtTokenUtil,
            TokenBlacklistService tokenBlacklistService,
            ProblemAuthenticationEntryPoint authenticationEntryPoint,
            ProblemResponseWriter problemResponseWriter
    ) {
        this.jwtTokenUtil = jwtTokenUtil;
        this.tokenBlacklistService = tokenBlacklistService;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.problemResponseWriter = problemResponseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/v3/api-docs/")
                || path.startsWith("/swagger-ui/")
                || path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/refresh")
                || path.equals("/api/v1/auth/logout")
                || path.equals("/admin/api/v1/auth/login")
                || path.equals("/admin/api/v1/auth/refresh")
                || path.equals("/admin/api/v1/auth/logout")
                || path.equals("/agent/api/v1/auth/token-exchange");
    }

    @Override
    protected void doFilterInternal(
            @Nonnull HttpServletRequest request,
            @Nonnull HttpServletResponse response,
            @Nonnull FilterChain chain
    ) throws ServletException, IOException {
        String token = extractBearer(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            IdentityType expected = expectedIdentity(request.getRequestURI());
            if (expected == null) {
                throw new BadCredentialsException("Bearer token is not accepted on this boundary");
            }
            ValidatedToken validated = jwtTokenUtil.validate(token, expected);
            if (tokenBlacklistService.isBlacklisted(validated.jti())) {
                throw new BadCredentialsException("Bearer token is revoked");
            }
            if (expected == IdentityType.AGENT_DELEGATION
                    && !AgentTokenExchangeService.ALLOWED_SCOPES.containsAll(validated.scopes())) {
                throw new BadCredentialsException("Agent scope is not allowed");
            }
            CustomUserDetails principal = principal(validated);
            UsernamePasswordAuthenticationToken authentication =
                    UsernamePasswordAuthenticationToken.authenticated(
                            principal,
                            null,
                            principal.getAuthorities()
                    );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } catch (DataAccessException exception) {
            SecurityContextHolder.clearContext();
            problemResponseWriter.write(
                    request,
                    response,
                    ApiException.serviceUnavailable(
                            "AUTHENTICATION_SERVICE_UNAVAILABLE",
                            "Authentication services are temporarily unavailable"
                    )
            );
        } catch (JwtException | IllegalArgumentException | BadCredentialsException exception) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new BadCredentialsException("Invalid bearer token")
            );
        }
    }

    private CustomUserDetails principal(ValidatedToken token) {
        Set<String> authorities = new LinkedHashSet<>();
        switch (token.identityType()) {
            case USER_ACCESS -> authorities.add("ROLE_USER");
            case ADMINISTRATOR_ACCESS -> authorities.addAll(JwtTokenUtil.administratorAuthorities());
            case AGENT_DELEGATION -> {
                authorities.add("AGENT_DELEGATION");
                token.scopes().forEach(scope -> authorities.add("SCOPE_" + scope));
            }
        }
        return CustomUserDetails.builder()
                .userId(token.subjectUserId())
                .username(token.username())
                .password("")
                .authorities(authorities.stream().map(SimpleGrantedAuthority::new).toList())
                .identityType(token.identityType())
                .tokenId(token.jti())
                .tokenExpiresAt(token.expiresAt())
                .authorizedParty(token.authorizedParty())
                .scopes(token.scopes())
                .build();
    }

    private IdentityType expectedIdentity(String path) {
        if (path.startsWith("/api/v1/")) {
            return IdentityType.USER_ACCESS;
        }
        if (path.startsWith("/admin/api/v1/")) {
            return IdentityType.ADMINISTRATOR_ACCESS;
        }
        if (path.startsWith("/agent/api/v1/")) {
            return IdentityType.AGENT_DELEGATION;
        }
        return null;
    }

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            return StringUtils.hasText(token) ? token : null;
        }
        return null;
    }
}
