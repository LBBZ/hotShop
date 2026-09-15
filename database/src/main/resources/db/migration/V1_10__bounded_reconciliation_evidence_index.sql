-- The existing (reservation_no, status, processing_id) index cannot serve
-- keyset pagination ordered by processing_id across statuses without sorting.
CREATE INDEX idx_seckill_processing_reservation_cursor
    ON seckill_event_processing (reservation_no, processing_id);
