package com.xh.mall.util;

public enum OrderStatus {
    PENDING("待支付"),
    PAID("已支付"),
    SHIPPING("已发货"),
    COMPLETED("已完成"),
    CANCELED("已取消");

    private final String value;

    OrderStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
