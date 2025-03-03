package com.real.common.enums;

import lombok.Getter;

@Getter
public enum TokenType {
    ACCESS("access"),
    REFRESH("refresh");

    private final String value;

    TokenType(String value) {
        this.value = value;
    }

    public static TokenType strTransitionToEnums(String tokenType) {
        return switch (tokenType) {
            case "ACCESS", "access" -> ACCESS;
            case "REFRESH", "refresh" -> REFRESH;
            default -> null;
        };
    }

}
