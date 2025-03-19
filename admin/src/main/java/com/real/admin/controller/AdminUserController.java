package com.real.admin.controller;

import com.github.pagehelper.PageInfo;
import com.real.common.enums.Role;
import com.real.domain.entity.User;
import com.real.domain.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@Tag(name = "管理端-用户管理", description = "用户数据管理（需管理员权限）")
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ROLE_ADMIN')")
public class AdminUserController {
    private final UserService userService;
    @Autowired
    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(
        summary = "多条件分页查询用户",
        description = "管理员根据复杂条件筛选用户列表",
        security = @SecurityRequirement(name = "JWT"),
        parameters = {
            @Parameter(name = "pageNum", description = "页码", example = "1"),
            @Parameter(name = "pageSize", description = "每页数量", example = "10"),
            @Parameter(name = "userId", description = "用户ID精确查询", example = "123"),
            @Parameter(name = "username", description = "用户名模糊查询", example = "john"),
            @Parameter(name = "email", description = "邮箱精确查询", example = "user@example.com"),
            @Parameter(name = "role", description = "用户角色",
                schema = @Schema(implementation = Role.class, example = "ROLE_ADMIN")),
            @Parameter(name = "startTime", description = "注册时间范围起",
                example = "2023-01-01T00:00:00"),
            @Parameter(name = "endTime", description = "注册时间范围止",
                example = "2023-12-31T23:59:59")
        },
        responses = @ApiResponse(
                content = @Content(schema = @Schema(implementation = PageInfo.class, example = """
            {
              "pageNum": 1,
              "pageSize": 10,
              "total": 100,
              "list": [
                {
                  "userId": 123,
                  "username": "admin",
                  "email": "admin@example.com",
                  "role": "ROLE_ADMIN",
                  "createdAt": "2023-01-01T00:00:00"
                }
              ]
            }"""))
        )
    )

    @GetMapping("/search")
    public ResponseEntity<PageInfo<User>> getUsers(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) LocalDateTime startTime,
            @RequestParam(required = false) LocalDateTime endTime
    ) {

        return ResponseEntity.ok(userService.getUserByConditions(
                pageNum, pageSize, userId, username, email, role, startTime, endTime
        ));
    }
}

