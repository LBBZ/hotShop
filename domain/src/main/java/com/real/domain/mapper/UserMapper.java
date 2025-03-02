package com.real.domain.mapper;

import com.real.domain.entity.baseEntity.User;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface UserMapper {

    int insert(User user);
    int delete(Long id);
    int deleteByUsername(String username);
    int update(User user);
    User selectByUsername(String username);
    List<User> selectAll();
    boolean existsByEmail(String email);
    boolean existsByUsername(String username);

}