package io.github.nitouge.cache.test.controller;

import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.CacheManager;
import io.github.nitouge.cache.test.entity.User;
import io.github.nitouge.cache.test.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private CacheManager cacheManager;

    /**
     * 根据ID查询用户
     */
    @GetMapping("/{id}")
    public Map<String, Object> getUserById(@PathVariable Long id) {
        long startTime = System.currentTimeMillis();
        User user = userService.getUserById(id);
        long endTime = System.currentTimeMillis();
        
        Map<String, Object> result = new HashMap<>();
        result.put("data", user);
        result.put("queryTime", endTime - startTime + "ms");
        result.put("message", "第一次查询会较慢，后续查询会从缓存获取");
        return result;
    }

    /**
     * 查询所有用户
     */
    @GetMapping
    public Map<String, Object> getAllUsers() {
        long startTime = System.currentTimeMillis();
        List<User> users = userService.getAllUsers();
        long endTime = System.currentTimeMillis();
        
        Map<String, Object> result = new HashMap<>();
        result.put("data", users);
        result.put("queryTime", endTime - startTime + "ms");
        result.put("count", users.size());
        return result;
    }

    /**
     * 根据用户名查询用户
     */
    @GetMapping("/username/{username}")
    public Map<String, Object> getUserByUsername(@PathVariable String username) {
        long startTime = System.currentTimeMillis();
        User user = userService.getUserByUsername(username);
        long endTime = System.currentTimeMillis();
        
        Map<String, Object> result = new HashMap<>();
        result.put("data", user);
        result.put("queryTime", endTime - startTime + "ms");
        return result;
    }

    /**
     * 创建用户
     */
    @PostMapping
    public Map<String, Object> createUser(@RequestBody User user) {
        User created = userService.createUser(user);
        
        Map<String, Object> result = new HashMap<>();
        result.put("data", created);
        result.put("message", "用户创建成功");
        return result;
    }

    /**
     * 更新用户
     */
    @PutMapping("/{id}")
    public Map<String, Object> updateUser(@PathVariable Long id, @RequestBody User user) {
        user.setId(id);
        User updated = userService.updateUser(user);
        
        Map<String, Object> result = new HashMap<>();
        result.put("data", updated);
        result.put("message", "用户更新成功，缓存已更新");
        return result;
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", "用户删除成功，缓存已清除");
        return result;
    }

    /**
     * 清空用户缓存
     */
    @DeleteMapping("/cache/clear")
    public Map<String, Object> clearCache() {
        userService.clearUserCache();
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", "用户缓存已清空");
        return result;
    }

    /**
     * 批量查询用户
     */
    @PostMapping("/batch")
    public Map<String, Object> getUsersByIds(@RequestBody List<Long> ids) {
        long startTime = System.currentTimeMillis();
        List<User> users = userService.getUsersByIds(ids);
        long endTime = System.currentTimeMillis();
        
        Map<String, Object> result = new HashMap<>();
        result.put("data", users);
        result.put("queryTime", endTime - startTime + "ms");
        result.put("count", users.size());
        return result;
    }

    /**
     * 使用CacheManager直接操作缓存
     */
    @GetMapping("/cache/direct/{id}")
    public Map<String, Object> getFromCacheDirect(@PathVariable Long id) {
        Cache cache = cacheManager.getCache("user");
        
        Map<String, Object> result = new HashMap<>();
        if (cache != null) {
            Object value = cache.get(id);
            result.put("data", value);
            result.put("cached", value != null);
            result.put("message", "直接从CacheManager获取缓存");
        } else {
            result.put("message", "缓存不存在");
        }
        return result;
    }

    /**
     * 使用CacheManager直接设置缓存
     */
    @PostMapping("/cache/direct")
    public Map<String, Object> putToCacheDirect(@RequestBody User user) {
        Cache cache = cacheManager.getCache("user");
        
        Map<String, Object> result = new HashMap<>();
        if (cache != null) {
            cache.put(user.getId(), user);
            result.put("message", "缓存设置成功");
            result.put("data", user);
        } else {
            result.put("message", "缓存不存在");
        }
        return result;
    }

    /**
     * 获取缓存统计信息
     */
    @GetMapping("/cache/stats")
    public Map<String, Object> getCacheStats() {
        Map<String, Object> result = new HashMap<>();
        result.put("cacheNames", cacheManager.getCacheNames());
        result.put("message", "缓存统计信息");
        return result;
    }

    /**
     * 测试批量操作API
     */
    @PostMapping("/cache/batch-test")
    public Map<String, Object> testBatchOperations(@RequestBody List<Long> ids) {
        Cache cache = cacheManager.getCache("user");
        
        Map<String, Object> result = new HashMap<>();
        if (cache != null) {
            // 批量获取
            long startTime = System.currentTimeMillis();
            Map<Long, User> batchResult = cache.batchGet(ids);
            long endTime = System.currentTimeMillis();
            
            result.put("data", batchResult);
            result.put("queryTime", endTime - startTime + "ms");
            result.put("hitCount", batchResult.size());
            result.put("totalCount", ids.size());
            result.put("message", "批量获取缓存测试");
        } else {
            result.put("message", "缓存不存在");
        }
        return result;
    }

}
