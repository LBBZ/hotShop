package com.real.domain.entity.baseEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Product {

    private Long productId;
    private String name;
    private BigDecimal price;
    private Integer stock;
    private String category;
    private String description;
    private LocalDateTime createdAt;

}