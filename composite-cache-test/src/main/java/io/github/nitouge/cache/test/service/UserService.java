package io.github.nitouge.cache.test.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.nitouge.cache.annotation.CacheAble;
import io.github.nitouge.cache.annotation.CacheEvict;
import io.github.nitouge.cache.annotation.CachePut;
import io.github.nitouge.cache.core.consts.enums.CacheModeEnum;
import io.github.nitouge.cache.test.entity.User;
import io.github.nitouge.cache.test.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 用户服务（带缓存）
 *
 */
@Slf4j
@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    /**
     * 根据ID查询用户（带缓存）
     */
    @CacheAble(
            cacheName = "user",
            keyExpr = "#id",
            cacheMode = CacheModeEnum.L1_L2
    )
    public User getUserById(Long id) {
        log.info("从数据库查询用户, id={}", id);
        simulateSlowQuery();
        return userMapper.selectById(id);
    }

    /**
     * 查询所有用户（带缓存）
     */
    @CacheAble(
            cacheName = "userList",
            keyExpr = "'all'",
            cacheMode = CacheModeEnum.L1_L2
    )
    public List<User> getAllUsers() {
        log.info("从数据库查询所有用户");
        simulateSlowQuery();
        return userMapper.selectList(null);
    }

    /**
     * 根据用户名查询用户（带缓存）
     */
    @CacheAble(
            cacheName = "user",
            keyExpr = "'username:' + #username",
            cacheMode = CacheModeEnum.L1_L2
    )
    public User getUserByUsername(String username) {
        log.info("从数据库查询用户, username={}", username);
        simulateSlowQuery();
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        return userMapper.selectOne(wrapper);
    }

    /**
     * 更新用户（更新缓存）
     */
    @CachePut(
            cacheName = "user",
            keyExpr = "#user.id",
            cacheMode = CacheModeEnum.L1_L2
    )
    public User updateUser(User user) {
        log.info("更新用户, user={}", user);
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
        return user;
    }

    /**
     * 删除用户（清除缓存）
     */
    @CacheEvict(
            cacheName = "user",
            keyExpr = "#id"
    )
    public void deleteUser(Long id) {
        log.info("删除用户, id={}", id);
        userMapper.deleteById(id);
    }

    /**
     * 清空所有用户缓存
     */
    @CacheEvict(
            cacheName = "user",
            removeAll = true
    )
    public void clearUserCache() {
        log.info("清空所有用户缓存");
    }

    /**
     * 创建用户
     */
    public User createUser(User user) {
        log.info("创建用户, user={}", user);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.insert(user);
        return user;
    }

    /**
     * 批量查询用户（演示批量操作）
     */
    public List<User> getUsersByIds(List<Long> ids) {
        log.info("批量查询用户, ids={}", ids);
        return userMapper.selectBatchIds(ids);
    }

    /**
     * 模拟慢查询
     */
    private void simulateSlowQuery() {
        try {
            Thread.sleep(500); // 模拟数据库查询延迟
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
