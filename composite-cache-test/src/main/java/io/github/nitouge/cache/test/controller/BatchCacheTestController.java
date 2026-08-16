package io.github.nitouge.cache.test.controller;

import io.github.nitouge.cache.test.dto.UserOrdersVO;
import io.github.nitouge.cache.test.entity.Order;
import io.github.nitouge.cache.test.entity.User;
import io.github.nitouge.cache.test.service.BatchCacheTestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 批量缓存测试控制器
 *
 */
@Slf4j
@RestController
@RequestMapping("/api/batch-cache")
public class BatchCacheTestController {

    @Autowired
    private BatchCacheTestService batchCacheTestService;

    /**
     * 测试1：批量获取用户（返回List）
     * 
     * GET /api/batch-cache/users?ids=1,2,3
     */
    @GetMapping("/users")
    public List<User> batchGetUsers(@RequestParam String ids) {
        List<Long> userIds = parseIds(ids);
        log.info("批量获取用户: {}", userIds);
        return batchCacheTestService.batchGetUsers(userIds);
    }

    /**
     * 测试2：批量获取用户（返回Map）
     * 
     * GET /api/batch-cache/users/map?ids=1,2,3
     */
    @GetMapping("/users/map")
    public Map<Long, User> batchGetUsersAsMap(@RequestParam String ids) {
        List<Long> userIds = parseIds(ids);
        log.info("批量获取用户（Map）: {}", userIds);
        return batchCacheTestService.batchGetUsersAsMap(userIds);
    }

    /**
     * 测试3：批量更新用户缓存
     * 
     * POST /api/batch-cache/users
     */
    @PostMapping("/users")
    public String batchPutUsers(@RequestBody List<User> users) {
        log.info("批量更新用户缓存: {} 个用户", users.size());
        batchCacheTestService.batchPutUsers(users);
        return "批量更新了 " + users.size() + " 个用户缓存";
    }

    /**
     * 测试4：批量删除用户缓存
     * 
     * DELETE /api/batch-cache/users?ids=1,2,3
     */
    @DeleteMapping("/users")
    public String batchEvictUsers(@RequestParam String ids) {
        List<Long> userIds = parseIds(ids);
        log.info("批量删除用户缓存: {}", userIds);
        batchCacheTestService.batchEvictUsers(userIds);
        return "批量删除了 " + userIds.size() + " 个用户缓存";
    }

    /**
     * 测试5：级联加载 - 用户 + 订单
     * 
     * GET /api/batch-cache/users-with-orders?ids=1,2,3
     */
    @GetMapping("/users-with-orders")
    public List<User> getUsersWithOrders(@RequestParam String ids) {
        List<Long> userIds = parseIds(ids);
        log.info("级联加载用户和订单: {}", userIds);
        return batchCacheTestService.getUsersWithOrders(userIds);
    }

    /**
     * 测试6：二级级联加载 - 用户 + 订单 + 商品
     * 
     * GET /api/batch-cache/users-with-orders-products?ids=1,2,3
     */
    @GetMapping("/users-with-orders-products")
    public List<User> getUsersWithOrdersAndProducts(@RequestParam String ids) {
        List<Long> userIds = parseIds(ids);
        log.info("二级级联加载用户、订单和商品: {}", userIds);
        return batchCacheTestService.getUsersWithOrdersAndProducts(userIds);
    }

    /**
     * 测试7：订单 + 商品级联加载
     * 
     * GET /api/batch-cache/orders-with-products?ids=1,2,3
     */
    @GetMapping("/orders-with-products")
    public List<Order> getOrdersWithProducts(@RequestParam String ids) {
        List<Long> orderIds = parseIds(ids);
        log.info("级联加载订单和商品: {}", orderIds);
        return batchCacheTestService.getOrdersWithProducts(orderIds);
    }

    /**
     * 测试8：完整的批量操作流程
     * 
     * POST /api/batch-cache/complete-flow?ids=1,2,3
     */
    @PostMapping("/complete-flow")
    public String testCompleteBatchFlow(@RequestParam String ids) {
        List<Long> userIds = parseIds(ids);
        log.info("测试完整批量操作流程: {}", userIds);
        batchCacheTestService.testCompleteBatchFlow(userIds);
        return "完整批量操作流程测试完成";
    }

    /**
     * 测试9：级联加载（安全版）- 用户 + 订单，返回组合 DTO（不污染缓存）
     *
     * GET /api/batch-cache/users-with-orders-vo?ids=1,2,3
     */
    @GetMapping("/users-with-orders-vo")
    public List<UserOrdersVO> getUsersWithOrdersVO(@RequestParam String ids) {
        List<Long> userIds = parseIds(ids);
        log.info("级联加载（安全版 DTO）用户和订单: {}", userIds);
        return batchCacheTestService.getUsersWithOrdersVO(userIds);
    }

    /**
     * 解析ID字符串
     */
    private List<Long> parseIds(String ids) {
        return Arrays.stream(ids.split(","))
            .map(String::trim)
            .map(Long::parseLong)
            .collect(java.util.stream.Collectors.toList());
    }
}
