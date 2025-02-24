package com.xh.mall.util;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StoredProcedure {

    void resetAutoIncrement(String tableName);

}
