package com.real.domain.entity.baseEntity;

import com.real.common.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class User {

    private Long id;
    private String username;
    private String password;
    private String email;
    private Role role;
    private LocalDateTime createdAt;

}
