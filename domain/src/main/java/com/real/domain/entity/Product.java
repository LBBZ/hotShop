package com.real.domain.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.type.Alias;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "商品实体")
public class Product {
    @Schema(description = "商品ID", example = "456")
    private Long productId;

    @Schema(description = "商品名称", example = "iPhone 15", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "商品价格（单位：元）", example = "6999.00")
    private BigDecimal price;

    @Schema(description = "库存数量", example = "100")
    private Integer stock;

    @Schema(description = "商品分类", example = "电子产品")
    private String category;

    @Schema(description = "商品描述", example = "最新款智能手机")
    private String description;

    @Schema(description = "创建时间", example = "2023-10-01T12:00:00")
    private LocalDateTime createdAt;
}