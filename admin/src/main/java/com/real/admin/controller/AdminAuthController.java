package com.real.admin.controller;

import com.real.common.enums.Role;
import com.real.common.enums.TokenType;
import com.real.domain.requestEntity.LoginRequest;
import com.real.domain.entity.User;
import com.real.domain.service.baseService.UserService;
import com.real.security.entity.CustomUserDetails;
import com.real.security.service.TokenBlacklistService;
import com.real.security.util.JwtTokenUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@Tag(name = "管理端-认证管理", description = "管理员账户认证管理")
@RequestMapping("/admin/auth")
public class AdminAuthController {
    private final AuthenticationManager authenticationManager;
    private final JwtTokenUtil jwtTokenUtil;
    private final UserService userService;
    private final TokenBlacklistService tokenBlacklistService;
    @Autowired
    public AdminAuthController(AuthenticationManager authenticationManager, JwtTokenUtil jwtTokenUtil, UserService userService, TokenBlacklistService tokenBlacklistService) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenUtil = jwtTokenUtil;
        this.userService = userService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Operation(
            summary = "管理员登录",
            description = "获取访问令牌（Access Token）",
            responses = {
                    @ApiResponse(responseCode = "200", description = "登录成功",
                            content = @Content(schema = @Schema(implementation = Map.class, example = """
            {
                "role": "ROLE_ADMIN",
                "userId": 123,
                "username": "john_doe",
                "access_token": "eyJhbGci...",
                "expires_in": 3600
            }"""))),
                    @ApiResponse(responseCode = "401", description = "用户名或密码错误")
            }
    )
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        // 1. 认证用户凭证
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(),
                        request.getPassword()
                )
        );

        // 2. 获取用户详情
        CustomUserDetails customUserDetails = (CustomUserDetails) authentication.getPrincipal();
        User user = userService.getUserByUsername(request.getUsername());
        if (!user.getRole().equals(Role.ROLE_ADMIN)) {
            return ResponseEntity.ok()
                    .body(Map.of("message", "user :" + customUserDetails.getUsername() + "not exist"));
        }

        // 3.生成令牌
        HashMap<String, Object> claims = new HashMap<>();
        claims.put("tokenType", TokenType.ACCESS);
        String accessToken = jwtTokenUtil.buildToken(claims, user.getUsername(), 86400L);

        // 4. 返回响应（刷新令牌建议通过Cookie返回）
        return ResponseEntity.ok()
                .body(Map.of(
                        "role", user.getRole(),
                        "userId", user.getUserId(),
                        "username", user.getUsername(),
                        "access_token", accessToken,
                        "expires_in", jwtTokenUtil.getExpirationDateFromToken(accessToken)
                ));
    }

    @Operation(
            summary = "管理员登出",
            description = "使当前访问令牌失效（需要携带有效令牌）",
            security = @SecurityRequirement(name = "JWT"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "登出成功"),
                    @ApiResponse(responseCode = "401", description = "未授权访问")
            }
    )
    @PostMapping("/logout")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<?> logout(
            HttpServletRequest request
    ) {
        String TokenAc = jwtTokenUtil.extractToken(request.getHeader("Authorization"));

        // 将令牌加入黑名单
        tokenBlacklistService.addToBlacklist(TokenAc);

        return ResponseEntity.ok(Map.of("message", "Logout successful"));
    }
}
