package io.github.nitouge.cache.test.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.nitouge.cache.annotation.CacheAble;
import io.github.nitouge.cache.annotation.CacheEvict;
import io.github.nitouge.cache.annotation.CachePut;
import io.github.nitouge.cache.annotation.Cache_L2;
import io.github.nitouge.cache.core.consts.enums.CacheModeEnum;
import io.github.nitouge.cache.core.consts.enums.RedisLoadStrategyEnum;
import io.github.nitouge.cache.test.entity.User;
import io.github.nitouge.cache.test.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
            cacheMode = CacheModeEnum.L1_L2,
            cacheL2 = @Cache_L2(
                    TTL = -1,
                    loadStrategy = RedisLoadStrategyEnum.LOCK
            )
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
     * 创建用户（创建后立即写入缓存）
     */
    @CachePut(
            cacheName = "user",
            keyExpr = "#result.id"
    )
    public User createUser(User user) {
        log.info("创建用户, user={}", user);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.insert(user);
        log.info("用户创建成功, id={}", user.getId());
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

    /**
     * 热点数据查询 - 使用逻辑过期策略（注解级别配置）
     * <p>
     * 特性：
     * 1. 使用 LOGICAL_EXPIRE 策略，永不阻塞
     * 2. logicalExpirePhysicalTtlFactor = 0，Redis key 永不过期
     * 3. 适合高并发热点数据
     */
    @CacheAble(
            cacheName = "hotUser",
            keyExpr = "#id",
            cacheMode = CacheModeEnum.L2,
            cacheL2 = @Cache_L2(
                    TTL = 60,
                    timeUnit = TimeUnit.SECONDS,
                    loadStrategy = RedisLoadStrategyEnum.LOGICAL_EXPIRE,
                    logicalExpirePhysicalTtlFactor = 0  // 不设置物理TTL
            )
    )
    public User getHotUser(Long id) {
        log.info("[热点数据] 从数据库查询用户, id={}", id);
        simulateSlowQuery();
        return userMapper.selectById(id);
    }

    /**
     * 普通热点数据查询 - 使用逻辑过期策略 + 物理TTL兜底
     * <p>
     * 特性：
     * 1. 使用 LOGICAL_EXPIRE 策略
     * 2. logicalExpirePhysicalTtlFactor = 3，物理TTL = 60s * 3 = 180s
     * 3. 提供兜底保护，防止异步刷新失败导致脏数据永久存在
     */
    @CacheAble(
            cacheName = "warmUser",
            keyExpr = "#id",
            cacheMode = CacheModeEnum.L2,
            cacheL2 = @Cache_L2(
                    TTL = 60,
                    timeUnit = TimeUnit.SECONDS,
                    loadStrategy = RedisLoadStrategyEnum.LOGICAL_EXPIRE,
                    logicalExpirePhysicalTtlFactor = 3  // 物理TTL = 逻辑TTL * 3
            )
    )
    public User getWarmUser(Long id) {
        log.info("[普通热点] 从数据库查询用户, id={}", id);
        simulateSlowQuery();
        return userMapper.selectById(id);
    }

    /**
     * 使用分布式锁策略的查询（注解级别配置）
     * <p>
     * 特性：
     * 1. 使用 LOCK 策略，避免缓存击穿
     * 2. 适合普通数据，非热点场景
     */
    @CacheAble(
            cacheName = "normalUser",
            keyExpr = "#id",
            cacheMode = CacheModeEnum.L2,
            cacheL2 = @Cache_L2(
                    TTL = 300,
                    timeUnit = TimeUnit.SECONDS,
                    loadStrategy = RedisLoadStrategyEnum.LOCK
            )
    )
    public User getNormalUser(Long id) {
        log.info("[普通数据] 从数据库查询用户, id={}", id);
        simulateSlowQuery();
        return userMapper.selectById(id);
    }

}
