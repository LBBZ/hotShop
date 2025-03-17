package com.real.domain.entity;

import com.real.common.enums.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.type.Alias;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "用户实体")
public class User {
    @Schema(description = "用户ID", example = "123")
    private Long userId;

    @Schema(description = "用户名", example = "john_doe", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    @Schema(description = "加密后的密码", example = "$2a$10$ABC...", hidden = true) // 隐藏敏感字段
    private String password;

    @Schema(description = "用户邮箱", example = "user@example.com")
    private String email;

    @Schema(
            description = "用户角色",
            example = "ROLE_USER",
            allowableValues = {"ROLE_ADMIN", "ROLE_USER"}
    )
    private Role role;

    @Schema(description = "创建时间", example = "2023-10-01T12:00:00")
    private LocalDateTime createdAt;
}