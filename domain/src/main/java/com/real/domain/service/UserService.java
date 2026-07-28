package com.real.domain.service;


import com.github.pagehelper.PageInfo;
import com.real.common.api.CursorCodec;
import com.real.common.api.CursorSlice;
import com.real.common.enums.Role;
import com.real.common.util.PageHelperUtils;
import com.real.domain.entity.User;
import com.real.domain.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class UserService {
    private final PageHelperUtils<User> pageHelperUtils;
    private final UserMapper userMapper;
    @Autowired
    public UserService(PageHelperUtils<User> pageHelperUtils, UserMapper userMapper) {
        this.pageHelperUtils = pageHelperUtils;
        this.userMapper = userMapper;
    }

    public void register(User user) {
        userMapper.insert(user);
    }

    public User getUserByUsername(String username) {
        return userMapper.selectByUsername(username);
    }
    public PageInfo<User> getUserByConditions(
            int pageNum, int pageSize,
            Long userId, String username, String email, Role role,
            LocalDateTime startTime, LocalDateTime endTime
    ) {
        List<User> users = userMapper.selectUsersByConditions(userId, username, email, role, startTime, endTime);
        return pageHelperUtils.getPageInfo(pageNum, pageSize, users);

    }

    public Boolean userExistsByUsername(String username) {
        return userMapper.existsByUsername(username);
    }
    public Boolean userExistsByEmail(String email) {
        return userMapper.existsByEmail(email);
    }

    public CursorSlice<User> getUsersByCursor(
            int limit,
            String cursor,
            Long userId,
            String username,
            String email,
            Role role,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        CursorCodec.TimeLongCursor decoded = CursorCodec.decodeTimeAndLong(cursor, "admin-users");
        List<User> fetched = userMapper.selectUsersByCursor(
                userId,
                username,
                email,
                role,
                startTime,
                endTime,
                decoded == null ? null : decoded.time(),
                decoded == null ? null : decoded.id(),
                limit + 1
        );
        boolean hasMore = fetched.size() > limit;
        List<User> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String nextCursor = hasMore
                ? CursorCodec.encodeTimeAndLong(
                        "admin-users",
                        items.get(items.size() - 1).getCreatedAt(),
                        items.get(items.size() - 1).getUserId()
                )
                : null;
        return new CursorSlice<>(items, nextCursor, hasMore);
    }
}
