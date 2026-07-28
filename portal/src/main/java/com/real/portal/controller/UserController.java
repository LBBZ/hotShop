package com.real.portal.controller;

import com.real.common.api.ApiException;
import com.real.common.api.dto.UserResponse;
import com.real.domain.api.ApiDtoMapper;
import com.real.domain.entity.User;
import com.real.domain.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "User profile", description = "Authenticated User profile")
@RequestMapping("/api/v1/users")
@PreAuthorize("hasAuthority('ROLE_USER')")
@SecurityRequirement(name = "bearerAuth")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "Get the current User")
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = userService.getUserByUsername(userDetails.getUsername());
        if (user == null) {
            throw ApiException.notFound("User");
        }
        return ResponseEntity.ok(ApiDtoMapper.toUserResponse(user));
    }
}
