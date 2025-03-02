package com.real.common.enums;

import lombok.Getter;

@Getter
public enum Role {
    ROLE_USER("普通用户"),   // 普通用户：查看、下单、取消订单
    ROLE_ADMIN("管理员");   // 管理员：商品管理、订单管理、用户管理

    private final String value;

    Role(String value) {
        this.value = value;
    }

    public static Role strTransitionToEnums(String role) {
        return switch (role) {
            case "普通用户", "USER","ROLE_USER" -> ROLE_USER;
            case "管理员", "ADMIN", "ROLE_ADMIN" -> ROLE_ADMIN;
            default -> null;
        };
    }
}
