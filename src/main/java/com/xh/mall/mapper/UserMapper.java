package com.xh.mall.mapper;

import com.xh.mall.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper {
    void insert(User user);
    User findByUsername(String username);
}