package com.real.domain.mapper;

import com.real.domain.entity.FlashSaleActivityFact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FlashSaleActivityMapper {
    FlashSaleActivityFact findFactById(@Param("activityId") long activityId);
}
