package com.real.common.enums;

import lombok.Getter;

@Getter
public enum OrderStatus {
    PENDING("待支付"),
    PAID("已支付"),
    SHIPPED("已发货"),
    COMPLETED("已完成"),
    CANCELED("已取消");

    private final String value;

    OrderStatus(String value) {
        this.value = value;
    }

    public boolean canTransitionTo(OrderStatus newStatus) {
        // 定义状态转换规则
        switch (this) {
            case PENDING:
                return newStatus == PAID || newStatus == CANCELED;
            case PAID:
                return newStatus == SHIPPED || newStatus == CANCELED;
            case SHIPPED:
                return newStatus == COMPLETED || newStatus == CANCELED;
            case COMPLETED:
                return false; // 已完成的订单不能再次转换
            case CANCELED:
                return false; // 已取消的订单不能再次转换
            default:
                return false;
        }
    }
}
