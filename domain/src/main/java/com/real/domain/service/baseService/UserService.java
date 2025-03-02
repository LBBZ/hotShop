package com.real.domain.service.baseService;


import com.real.domain.entity.baseEntity.User;
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

    public Boolean userExistsByUsername(String username) {
        return userMapper.existsByUsername(username);
    }
    public Boolean userExistsByEmail(String email) {
        return userMapper.existsByEmail(email);
    }
}