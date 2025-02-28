package com.real.domain.mapper;

import com.real.domain.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper {

    int insert(User user);
    int update(User user);
    int delete(Long id);
    User selectByUsername(String username);

}