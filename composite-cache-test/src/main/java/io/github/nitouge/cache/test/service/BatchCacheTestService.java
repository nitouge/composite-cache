package io.github.nitouge.cache.test.service;

import io.github.nitouge.cache.core.api.CacheTemplate;
import io.github.nitouge.cache.test.dto.UserOrdersVO;
import io.github.nitouge.cache.test.entity.Order;
import io.github.nitouge.cache.test.entity.Product;
import io.github.nitouge.cache.test.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 批量缓存操作测试服务
 * 
 * <p>演示 BatchCacheOperations 的所有方法使用
 * 
 */
@Slf4j
@Service
public class BatchCacheTestService {

    @Autowired
    private CacheTemplate batchCacheOperations;

    @Autowired
    private UserService userService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductService productService;

    /**
     * 测试1：批量获取或加载用户（返回List）
     */
    public List<User> batchGetUsers(List<Long> userIds) {
        log.info("=== 测试批量获取用户 ===");
        
        return batchCacheOperations.batchGetOrLoadList(
            "user",
            userIds,
            User::getId,
            ids -> {
                log.info("缓存未命中，从数据库加载用户: {}", ids);
                return userService.getUsersByIds(ids);
            }
        );
    }

    /**
     * 测试2：批量获取或加载用户（返回Map）
     */
    public Map<Long, User> batchGetUsersAsMap(List<Long> userIds) {
        log.info("=== 测试批量获取用户（Map形式） ===");
        
        return batchCacheOperations.batchGetOrLoadAsMap(
            "user",
            userIds,
            User::getId,
            ids -> {
                log.info("缓存未命中，从数据库加载用户: {}", ids);
                return userService.getUsersByIds(ids);
            }
        );
    }

    /**
     * 测试3：批量更新用户缓存
     */
    public void batchPutUsers(List<User> users) {
        log.info("=== 测试批量更新用户缓存 ===");
        
        batchCacheOperations.batchPut(
            "user",
            users,
            User::getId
        );
        
        log.info("批量更新了 {} 个用户缓存", users.size());
    }

    /**
     * 测试4：批量删除用户缓存
     */
    public void batchEvictUsers(List<Long> userIds) {
        log.info("=== 测试批量删除用户缓存 ===");
        
        batchCacheOperations.batchEvict("user", userIds);
        
        log.info("批量删除了 {} 个用户缓存", userIds.size());
    }

    /**
     * 测试5：级联加载 - 用户 + 订单
     * 
     * <p>场景：查询用户列表，同时加载每个用户的订单
     */
    public List<User> getUsersWithOrders(List<Long> userIds) {
        log.info("=== 测试级联加载：用户 + 订单 ===");
        
        return batchCacheOperations.batchGetOrLoadWithRelated(
            "user",
            userIds,
            User::getId,
            // 主数据加载器：加载用户
            ids -> {
                log.info("加载用户主数据: {}", ids);
                return userService.getUsersByIds(ids);
            },
            // 关联数据加载器：加载订单并关联到用户
            users -> {
                log.info("加载用户关联的订单数据");
                List<Long> loadedUserIds = users.stream()
                    .map(User::getId)
                    .collect(Collectors.toList());
                
                // 批量查询订单
                List<Order> orders = orderService.getOrdersByUserIds(loadedUserIds);
                
                // 按用户ID分组订单
                Map<Long, List<Order>> orderMap = orders.stream()
                    .collect(Collectors.groupingBy(Order::getUserId));
                
                // 将订单关联到用户（这里使用transient字段或者返回DTO）
                users.forEach(user -> {
                    List<Order> userOrders = orderMap.get(user.getId());
                    log.info("用户 {} 有 {} 个订单", user.getUsername(), 
                        userOrders != null ? userOrders.size() : 0);
                });
                
                return orderMap;
            }
        );
    }

    /**
     * 测试6：二级级联加载 - 用户 + 订单 + 商品
     * 
     * <p>场景：查询用户列表，同时加载订单和订单中的商品信息
     */
    public List<User> getUsersWithOrdersAndProducts(List<Long> userIds) {
        log.info("=== 测试二级级联加载：用户 + 订单 + 商品 ===");
        
        return batchCacheOperations.batchGetOrLoadWithRelated(
            "user",
            userIds,
            User::getId,
            // 主数据加载器：加载用户
            ids -> {
                log.info("加载用户主数据: {}", ids);
                return userService.getUsersByIds(ids);
            },
            // 关联数据加载器：加载订单和商品
            users -> {
                log.info("加载用户关联的订单和商品数据");
                List<Long> loadedUserIds = users.stream()
                    .map(User::getId)
                    .collect(Collectors.toList());
                
                // 1. 批量查询订单
                List<Order> orders = orderService.getOrdersByUserIds(loadedUserIds);
                log.info("查询到 {} 个订单", orders.size());
                
                // 2. 提取商品ID并批量查询商品
                List<Long> productIds = orders.stream()
                    .map(Order::getProductId)
                    .distinct()
                    .collect(Collectors.toList());
                
                if (!productIds.isEmpty()) {
                    List<Product> products = batchCacheOperations.batchGetOrLoadList(
                        "product",
                        productIds,
                        Product::getId,
                        ids -> {
                            log.info("加载商品数据: {}", ids);
                            return productService.getProductsByIds(ids);
                        }
                    );
                    
                    // 3. 构建商品Map
                    Map<Long, Product> productMap = products.stream()
                        .collect(Collectors.toMap(Product::getId, p -> p));
                    
                    // 4. 将商品信息关联到订单
                    orders.forEach(order -> {
                        Product product = productMap.get(order.getProductId());
                        order.setProduct(product);
                    });
                    
                    log.info("关联了 {} 个商品到订单", products.size());
                }
                
                // 5. 按用户ID分组订单
                Map<Long, List<Order>> orderMap = orders.stream()
                    .collect(Collectors.groupingBy(Order::getUserId));
                
                // 6. 输出统计信息
                users.forEach(user -> {
                    List<Order> userOrders = orderMap.get(user.getId());
                    if (userOrders != null) {
                        log.info("用户 {} 有 {} 个订单，涉及 {} 个不同商品", 
                            user.getUsername(), 
                            userOrders.size(),
                            userOrders.stream().map(Order::getProductId).distinct().count());
                    }
                });
                
                return orderMap;
            }
        );
    }

    /**
     * 测试7：订单 + 商品级联加载
     * 
     * <p>场景：查询订单列表，同时加载商品信息
     */
    public List<Order> getOrdersWithProducts(List<Long> orderIds) {
        log.info("=== 测试级联加载：订单 + 商品 ===");
        
        return batchCacheOperations.batchGetOrLoadWithRelated(
            "order",
            orderIds,
            Order::getId,
            // 主数据加载器：加载订单
            ids -> {
                log.info("加载订单主数据: {}", ids);
                return orderService.getOrdersByIds(ids);
            },
            // 关联数据加载器：加载商品
            orders -> {
                log.info("加载订单关联的商品数据");
                
                // 提取商品ID
                List<Long> productIds = orders.stream()
                    .map(Order::getProductId)
                    .distinct()
                    .collect(Collectors.toList());
                
                if (productIds.isEmpty()) {
                    return new HashMap<>();
                }
                
                // 批量查询商品
                List<Product> products = batchCacheOperations.batchGetOrLoadList(
                    "product",
                    productIds,
                    Product::getId,
                    ids -> {
                        log.info("加载商品数据: {}", ids);
                        return productService.getProductsByIds(ids);
                    }
                );
                
                // 构建商品Map
                Map<Long, Product> productMap = products.stream()
                    .collect(Collectors.toMap(Product::getId, p -> p));
                
                // 将商品关联到订单
                orders.forEach(order -> {
                    Product product = productMap.get(order.getProductId());
                    order.setProduct(product);
                    log.info("订单 {} 关联商品 {}", order.getOrderNo(), 
                        product != null ? product.getProductName() : "null");
                });
                
                return productMap;
            }
        );
    }

    /**
     * 测试8.5：级联加载（<b>安全版</b>）—— 用户 + 订单，返回组合 DTO，<b>不修改被缓存对象</b>。
     *
     * <p>对比 {@link #getUsersWithOrders}/{@link #getUsersWithOrdersAndProducts}（用 setter 把关联挂到
     * 被缓存的 user/order 上，会污染 L1 缓存）；本方法用 {@code batchGetOrLoadCombined} 的 assembler
     * 组合出新的 {@link UserOrdersVO}，主对象保持只读。
     *
     * <p>同时演示 {@code CacheTemplate.chunkedLoadList}：把主数据回源按片切分（此处每片 2 个，便于在日志/调试中观察分片），
     * 避免超大 {@code IN(...)}。
     */
    public List<UserOrdersVO> getUsersWithOrdersVO(List<Long> userIds) {
        log.info("=== 级联加载（安全版 DTO）：用户 + 订单 ===");

        return batchCacheOperations.batchGetOrLoadCombined(
                "user",
                userIds,
                User::getId,
                // 主数据加载器：批量加载用户；用 chunkedLoadList 演示 DB 分片（每片 2 个）
                ids -> CacheTemplate.chunkedLoadList(ids, 2, chunk -> {
                    log.info("加载用户分片（来自 DB）: {}", chunk);
                    return userService.getUsersByIds(chunk);
                }),
                // 关联加载器：返回 userId -> 该用户的订单列表（仅返回，绝不修改主对象 user）
                users -> {
                    List<Long> uids = users.stream().map(User::getId).collect(Collectors.toList());
                    List<Order> orders = orderService.getOrdersByUserIds(uids);
                    log.info("加载到 {} 条订单，按 userId 分组", orders.size());
                    return orders.stream().collect(Collectors.groupingBy(Order::getUserId));
                },
                // assembler：组合出新的 UserOrdersVO（不 mutate user/order）
                (user, orders) -> new UserOrdersVO(user, orders == null ? java.util.Collections.emptyList() : orders));
    }

    /**
     * 测试8：完整的批量操作流程
     *
     * <p>演示：查询 -> 更新 -> 删除 的完整流程
     */
    public void testCompleteBatchFlow(List<Long> userIds) {
        log.info("=== 测试完整批量操作流程 ===");
        
        // 1. 批量查询
        log.info("步骤1：批量查询用户");
        List<User> users = batchCacheOperations.batchGetOrLoadList(
            "user",
            userIds,
            User::getId,
            ids -> userService.getUsersByIds(ids)
        );
        log.info("查询到 {} 个用户", users.size());
        
        // 2. 修改数据
        log.info("步骤2：修改用户数据");
        users.forEach(user -> {
            user.setAddress("Updated Address - " + System.currentTimeMillis());
        });
        
        // 3. 批量更新缓存
        log.info("步骤3：批量更新缓存");
        batchCacheOperations.batchPut("user", users, User::getId);
        log.info("更新了 {} 个用户缓存", users.size());
        
        // 4. 验证缓存
        log.info("步骤4：验证缓存（应该从缓存读取）");
        List<User> cachedUsers = batchCacheOperations.batchGetOrLoadList(
            "user",
            userIds,
            User::getId,
            ids -> {
                log.warn("不应该执行到这里！数据应该从缓存读取");
                return userService.getUsersByIds(ids);
            }
        );
        log.info("从缓存读取到 {} 个用户", cachedUsers.size());
        
        // 5. 批量删除缓存
        log.info("步骤5：批量删除缓存");
        batchCacheOperations.batchEvict("user", userIds);
        log.info("删除了 {} 个用户缓存", userIds.size());
        
        log.info("=== 完整批量操作流程测试完成 ===");
    }
}
