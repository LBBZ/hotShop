package com.xh.mall.mapper;

import com.xh.mall.entity.Product;
import com.xh.mall.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper {

    int insert(User user);
    int update(User user);
    int delete(Long id);
    User selectByUsername(String username);

}