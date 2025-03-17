package com.real.portal.controller;

import com.real.common.enums.Role;
import com.real.common.enums.TokenType;
import com.real.domain.requestEntity.LoginRequest;
import com.real.domain.requestEntity.RegisterRequest;
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
import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@Tag(name = "认证接口", description = "用户注册/登录/登出/令牌管理")
@RequestMapping("/portal/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final JwtTokenUtil jwtTokenUtil;
    private final UserService userService;
    private final UserDetailsService userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;
    @Autowired
    public AuthController(AuthenticationManager authenticationManager, JwtTokenUtil jwtTokenUtil, UserDetailsService userDetailsService, UserService userService, TokenBlacklistService tokenBlacklistService) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenUtil = jwtTokenUtil;
        this.userService = userService;
        this.userDetailsService = userDetailsService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Operation(
        summary = "用户注册",
        description = "新用户注册接口（密码需包含字母、数字、特殊字符且至少8位）",
        responses = {
                @ApiResponse(responseCode = "200", description = "注册成功"),
                @ApiResponse(responseCode = "400", description = """
            可能的错误信息：
            - 用户名已存在
            - 邮箱已被注册
            - 密码不符合强度要求
            """)
        }
    )
    @PostMapping("/register")
    public ResponseEntity<String> register(@RequestBody @Valid RegisterRequest request) {
        // 检查用户名、邮箱是否已存在
        if(userService.userExistsByUsername(request.getUsername())) {
            return ResponseEntity.badRequest().body("用户名已存在");
        }
        if(userService.userExistsByEmail(request.getEmail())) {
            return ResponseEntity.badRequest().body("邮箱已被注册");
        }
        if (!request.getPassword().matches("^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@$!%*#?&])[A-Za-z\\d@$!%*#?&]{8,}$")) {
            return ResponseEntity.badRequest().body("密码不符合强度要求");
        }
        // 创建用户实体
        User newUser = new User();
        newUser.setUsername(request.getUsername());
        PasswordEncoder passwordEncoder = new  BCryptPasswordEncoder();
        newUser.setPassword(passwordEncoder.encode(request.getPassword())); // 加密密码
        newUser.setEmail(request.getEmail());
        newUser.setRole(Role.ROLE_USER);

        userService.register(newUser);
        return ResponseEntity.ok("注册成功");
    }

    @Operation(
        summary = "普通用户登录",
        description = "获取访问令牌（Access Token）和刷新令牌（Refresh Token）",
        responses = {
            @ApiResponse(responseCode = "200", description = "登录成功",
                    content = @Content(schema = @Schema(implementation = Map.class, example = """
            {
                "role": "ROLE_USER",
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
        if (!user.getRole().equals(Role.ROLE_USER)) {
            return ResponseEntity.ok()
                    .body(Map.of("message", "user :" + customUserDetails.getUsername() + "not exist"));
        }

        // 3.生成双令牌
        String accessToken = jwtTokenUtil.generateToken(customUserDetails, TokenType.ACCESS, null);
        String refreshToken = jwtTokenUtil.generateToken(customUserDetails, TokenType.REFRESH, null);

        // 4. 返回响应（刷新令牌建议通过Cookie返回）
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, createRefreshTokenCookie(refreshToken).toString())
                .body(Map.of(
                        "role", user.getRole(),
                        "userId", user.getUserId(),
                        "username", user.getUsername(),
                        "access_token", accessToken,
                        "expires_in", jwtTokenUtil.getExpirationDateFromToken(accessToken)
                ));
    }

    @Operation(
        summary = "普通用户登出",
        description = "使当前访问令牌失效（需要携带有效令牌）",
        security = @SecurityRequirement(name = "JWT"),
        responses = {
            @ApiResponse(responseCode = "200", description = "登出成功"),
            @ApiResponse(responseCode = "401", description = "未授权访问")
        }
    )
    @PostMapping("/logout")
    @PreAuthorize("hasRole('ROLE_USER')")
    public ResponseEntity<?> logout(
            HttpServletRequest request,
            @CookieValue(name = "refresh_token", required = false) String refreshToken
    ) {
        String TokenAc = jwtTokenUtil.extractToken(request.getHeader("Authorization"));
        String TokenRe = jwtTokenUtil.extractToken(refreshToken);

        // 将令牌加入黑名单
        tokenBlacklistService.addToBlacklist(TokenAc);
        tokenBlacklistService.addToBlacklist(TokenRe);

        return ResponseEntity.ok(Map.of("message", "Logout successful"));
    }


    @Operation(
        summary = "刷新访问令牌",
        description = "使用刷新令牌获取新的访问令牌",
        responses = {
            @ApiResponse(responseCode = "200", description = "令牌刷新成功",
                    content = @Content(schema = @Schema(example = """
            { "access_token": "eyJhbGci..." }"""))),
            @ApiResponse(responseCode = "401", description = "无效的刷新令牌")
            }
    )
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(
            HttpServletRequest request,
            @CookieValue(name = "refresh_token", required = false) String refreshToken) {
        // 1. 验证刷新令牌格式,验证access令牌是否失效
        if (!jwtTokenUtil.validateToken(refreshToken, TokenType.REFRESH)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("无效令牌");
        }
        String accessToken = jwtTokenUtil.extractToken(request.getHeader("Authorization"));
        if (accessToken == null || accessToken.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("无效令牌");
        }
        if (jwtTokenUtil.validateToken(accessToken, TokenType.ACCESS)) {
            return ResponseEntity.ok(Map.of(
                    "access_token", accessToken
            ));
        }

        // 2. 提取用户信息
        String username = jwtTokenUtil.getUsernameFromToken(refreshToken);
        User user = userService.getUserByUsername(username);
        if(user == null) {
            throw new UsernameNotFoundException("User not found");
        }

        // 3. 生成新访问令牌
        String newAccessToken = jwtTokenUtil.generateToken(
                (CustomUserDetails) userDetailsService.loadUserByUsername(username),
                TokenType.ACCESS,
                null
        );

        return ResponseEntity.ok(Map.of(
                "access_token", newAccessToken
        ));
    }

    // 创建安全Cookie
    private ResponseCookie createRefreshTokenCookie(String token) {
        return ResponseCookie.from("refresh_token", token)
                .httpOnly(true)
                .secure(false) // 生产环境启用
                .path("/api/auth/refresh")
                .maxAge(Duration.ofSeconds(jwtTokenUtil.getRefreshExpiration()))
                .sameSite("Strict")
                .build();
    }
}
