package com.real.domain.service.seckill;

public enum FlashSaleLoadCode {
    LOADED,
    IDEMPOTENT,
    ACTIVITY_NOT_FOUND,
    ACTIVITY_INVALID,
    STALE_VERSION,
    RESERVATIONS_EXIST,
    INTERNAL_STATE_INVALID
}
