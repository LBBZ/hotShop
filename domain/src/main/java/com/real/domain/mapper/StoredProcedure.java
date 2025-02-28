package com.real.domain.mapper;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StoredProcedure {

    void resetAutoIncrement(String tableName);

}
