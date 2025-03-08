package com.real.portal.controller;


import com.real.domain.entity.baseEntity.User;
import com.real.domain.service.baseService.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "用户接口", description = "用户信息管理（需登录）")
@RequestMapping("/users")
@PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_ADMIN')")
public class UserController {
    private final UserService userService;
    @Autowired
    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(
        summary = "获取当前用户信息",
        description = "获取已登录用户的详细信息",
        security = @SecurityRequirement(name = "JWT"),
        responses = @ApiResponse(
                content = @Content(schema = @Schema(implementation = User.class, example = """
            {
                "userId": 123,
                "username": "john_doe",
                "email": "user@example.com",
                "role": "ROLE_USER",
                "createdAt": "2023-01-01T00:00:00"
            }""")))
    )
    @GetMapping("/me")
    public ResponseEntity<User> getCurrentUser(
            @AuthenticationPrincipal UserDetails userDetails) {

        User user = userService.getUserByUsername(userDetails.getUsername());
        if (user == null) {
            throw new UsernameNotFoundException("User not found");
        }

        User response = User.builder()
                .userId(user.getUserId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .build();

        return ResponseEntity.ok(response);
    }
}

