package com.real.admin.controller;

import com.real.common.api.ApiException;
import com.real.common.api.CursorSlice;
import com.real.common.api.dto.CursorPageResponse;
import com.real.common.api.dto.UserResponse;
import com.real.common.enums.Role;
import com.real.domain.api.ApiDtoMapper;
import com.real.domain.entity.User;
import com.real.domain.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@Validated
@Tag(name = "Admin users", description = "Administrator User queries")
@RequestMapping("/admin/api/v1/users")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {
    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(
            summary = "List Users",
            description = "Stable keyset pagination ordered by createdAt descending, then userId descending"
    )
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_ADMIN_USER_READ')")
    public ResponseEntity<CursorPageResponse<UserResponse>> getUsers(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) Long userId,
            @RequestParam(required = false) @Size(max = 64) String username,
            @RequestParam(required = false) @Email @Size(max = 254) String email,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo
    ) {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw ApiException.badRequest(
                    "TIME_RANGE_INVALID",
                    "createdFrom must be before or equal to createdTo"
            );
        }
        CursorSlice<User> slice = userService.getUsersByCursor(
                limit,
                cursor,
                userId,
                username,
                email,
                role,
                ApiDtoMapper.toUtcLocalDateTime(createdFrom),
                ApiDtoMapper.toUtcLocalDateTime(createdTo)
        );
        return ResponseEntity.ok(new CursorPageResponse<>(
                slice.items().stream().map(ApiDtoMapper::toUserResponse).toList(),
                slice.nextCursor(),
                slice.hasMore()
        ));
    }
}
