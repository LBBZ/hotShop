package com.real.domain.mapper;

import com.real.common.enums.Role;
import com.real.domain.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserMapper {

    int insert(User user);
    int delete(Long userId);
    int deleteByUsername(String username);
    int update(User user);
    User selectByUsername(String username);
    List<User> selectAll();
    List<User> selectUsersByConditions(@Param("userId") Long userId,
                                       @Param("username") String username,
                                       @Param("email") String email,
                                       @Param("role") Role role,
                                       @Param("startTime") LocalDateTime startTime,
                                       @Param("endTime") LocalDateTime endTime);
    List<User> selectUsersByCursor(
            @Param("userId") Long userId,
            @Param("username") String username,
            @Param("email") String email,
            @Param("role") Role role,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorUserId") Long cursorUserId,
            @Param("limit") int limit
    );

    boolean existsByEmail(String email);
    boolean existsByUsername(String username);
}
