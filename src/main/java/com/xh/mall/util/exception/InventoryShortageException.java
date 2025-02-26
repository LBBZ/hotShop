package com.xh.mall.util.exception;

public class InventoryShortageException extends RuntimeException {
    public InventoryShortageException(String message) {
        super(message);
    }
}
