package com.real.portal.controller;

import com.real.common.enums.Role;
import com.real.common.enums.TokenType;
import com.real.domain.entity.authEntity.LoginRequest;
import com.real.domain.entity.authEntity.RegisterRequest;
import com.real.domain.entity.baseEntity.User;
import com.real.domain.service.baseService.UserService;
import com.real.security.entity.CustomUserDetails;
import com.real.security.service.TokenBlacklistService;
import com.real.security.util.JwtTokenUtil;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
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

        // 3.生成双令牌
        String accessToken = jwtTokenUtil.generateToken(customUserDetails, TokenType.ACCESS);
        String refreshToken = jwtTokenUtil.generateToken(customUserDetails, TokenType.REFRESH);

        // 4. 返回响应（刷新令牌建议通过Cookie返回）
        User user = userService.getUserByUsername(request.getUsername());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, createRefreshTokenCookie(refreshToken).toString())
                .body(Map.of(
                        "role", user.getRole(),
                        "userId", user.getUserId(),
                        "username", user.getUsername(),
                        "access_token", accessToken,
                        "expires_in", jwtTokenUtil.getAccessExpiration()
                ));
    }

    @PostMapping("/logout")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_ADMIN')")
    public ResponseEntity<?> logout(
            HttpServletRequest request,
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            @AuthenticationPrincipal CustomUserDetails customUserDetails
    ) {
        String TokenAc = jwtTokenUtil.extractToken(request.getHeader("Authorization"));
        String TokenRe = jwtTokenUtil.extractToken(refreshToken);

        // 将令牌加入黑名单
        tokenBlacklistService.addToBlacklist(TokenAc);
        tokenBlacklistService.addToBlacklist(TokenRe);


        return ResponseEntity.ok(Map.of("message", "Logout successful"));
    }

    // 刷新令牌接口
    @PostMapping("/refresh")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_ADMIN')")
    public ResponseEntity<?> refreshToken(@CookieValue(name = "refresh_token", required = false) String refreshToken) {
        // 1. 验证刷新令牌格式
        if (!jwtTokenUtil.validateToken(refreshToken, TokenType.REFRESH)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("无效的刷新令牌");
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
                TokenType.ACCESS
        );

        return ResponseEntity.ok(Map.of(
                "access_token", newAccessToken,
                "expires_in", jwtTokenUtil.getAccessExpiration()
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
