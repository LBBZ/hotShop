package com.real.portal.userjourney;

import com.real.common.api.dto.FlashSaleActivityResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class FlashSaleActivityQueryService {
    private final JdbcTemplate jdbc;

    public FlashSaleActivityQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FlashSaleActivityResponse> currentAndUpcoming(int limit) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Instant serverTime = now.toInstant(ZoneOffset.UTC);
        return jdbc.query("""
                SELECT a.activity_id, a.activity_code, a.product_id, p.name, p.category,
                       p.description, a.sale_price, a.available_stock, a.per_user_limit,
                       a.status, a.starts_at, a.ends_at
                  FROM flash_sale_activity a
                  JOIN catalog_product p ON p.product_id = a.product_id
                 WHERE a.status IN ('SCHEDULED', 'ACTIVE')
                   AND a.ends_at > ?
                   AND p.status = 'ACTIVE'
                   AND p.deleted_at IS NULL
                 ORDER BY CASE WHEN a.starts_at <= ? THEN 0 ELSE 1 END,
                          a.starts_at ASC, a.activity_id ASC
                 LIMIT ?
                """, (rs, rowNum) -> {
            LocalDateTime startsAt = rs.getTimestamp("starts_at").toLocalDateTime();
            LocalDateTime endsAt = rs.getTimestamp("ends_at").toLocalDateTime();
            int stock = rs.getInt("available_stock");
            String phase = phase(now, startsAt, endsAt, stock);
            return new FlashSaleActivityResponse(
                    rs.getLong("activity_id"),
                    rs.getString("activity_code"),
                    rs.getLong("product_id"),
                    rs.getString("name"),
                    rs.getString("category"),
                    rs.getString("description"),
                    rs.getBigDecimal("sale_price").setScale(2, RoundingMode.UNNECESSARY),
                    stock,
                    rs.getInt("per_user_limit"),
                    rs.getString("status"),
                    phase,
                    startsAt.toInstant(ZoneOffset.UTC),
                    endsAt.toInstant(ZoneOffset.UTC),
                    serverTime
            );
        }, now, now, limit);
    }

    private static String phase(
            LocalDateTime now,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            int availableStock
    ) {
        if (!now.isBefore(endsAt)) {
            return "EXPIRED";
        }
        if (now.isBefore(startsAt)) {
            return "UPCOMING";
        }
        return availableStock > 0 ? "LIVE" : "SOLD_OUT";
    }
}
