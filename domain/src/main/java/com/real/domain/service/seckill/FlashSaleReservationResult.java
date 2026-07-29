package com.real.domain.service.seckill;

public record FlashSaleReservationResult(
        FlashSaleReservationCode code,
        String reservationNo,
        long activityId,
        String status,
        String requestId,
        String streamId
) {
    public boolean accepted() {
        return code == FlashSaleReservationCode.ACCEPTED
                || code == FlashSaleReservationCode.IDEMPOTENT_REPLAY;
    }

    public boolean replayed() {
        return code == FlashSaleReservationCode.IDEMPOTENT_REPLAY;
    }
}
