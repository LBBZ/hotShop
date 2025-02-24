package com.xh.mall.mapper;

import com.xh.mall.entity.Product;
import com.xh.mall.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper {

    void insert(User user);
    void update(Product product);
    void delete(Long id);
    User selectByUsername(String username);

}