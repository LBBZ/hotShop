package com.real.domain.service;


import com.real.domain.entity.User;
import com.real.domain.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserMapper userMapper;

    @Autowired
    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public void register(User user) {
        userMapper.insert(user);
    }

    public User getUserByUsername(String username) {
        return userMapper.selectByUsername(username);
    }
}