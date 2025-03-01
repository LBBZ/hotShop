package com.real.domain.mapper;

import com.real.domain.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper {

    int insert(User user);
    int delete(Long id);
    int update(User user);
    User selectByUsername(String username);

}