package com.real.common.exception;

public class SeckillServiceUnavailableException extends RuntimeException {
    public SeckillServiceUnavailableException(Throwable cause) {
        super("The flash-sale reservation service is unavailable", cause);
    }
}
