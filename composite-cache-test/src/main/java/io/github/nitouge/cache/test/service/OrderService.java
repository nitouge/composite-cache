package io.github.nitouge.cache.test.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.nitouge.cache.annotation.CacheAble;
import io.github.nitouge.cache.annotation.CacheEvict;
import io.github.nitouge.cache.annotation.CachePut;
import io.github.nitouge.cache.core.consts.enums.CacheModeEnum;
import io.github.nitouge.cache.test.entity.Order;
import io.github.nitouge.cache.test.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单服务
 * 
 */
@Slf4j
@Service
public class OrderService {

    @Autowired
    private OrderMapper orderMapper;

    /**
     * 根据ID查询订单（带缓存）
     */
    @CacheAble(
            cacheName = "order",
            keyExpr = "#id",
            cacheMode = CacheModeEnum.L1_L2
    )
    public Order getOrderById(Long id) {
        log.info("从数据库查询订单, id={}", id);
        simulateSlowQuery();
        return orderMapper.selectById(id);
    }

    /**
     * 根据用户ID查询订单列表（带缓存）。
     *
     * <p>用<b>独立的 cacheName</b> 隔离键空间：{@code List<Order>} 与单个 {@code Order}
     * （cacheName="order"）类型不同、不可共用同一缓存，否则同名缓存里混存两种类型、键空间冲突。
     */
    @CacheAble(
            cacheName = "orders_by_user",
            keyExpr = "#userId",
            cacheMode = CacheModeEnum.L1_L2
    )
    public List<Order> getOrdersByUserId(Long userId) {
        log.info("从数据库查询用户订单, userId={}", userId);
        simulateSlowQuery();
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getUserId, userId);
        return orderMapper.selectList(wrapper);
    }

    /**
     * 根据商品ID查询订单列表（带缓存）。独立 cacheName 隔离键空间（同上）。
     */
    @CacheAble(
            cacheName = "orders_by_product",
            keyExpr = "#productId",
            cacheMode = CacheModeEnum.L1_L2
    )
    public List<Order> getOrdersByProductId(Long productId) {
        log.info("从数据库查询商品订单, productId={}", productId);
        simulateSlowQuery();
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getProductId, productId);
        return orderMapper.selectList(wrapper);
    }

    /**
     * 批量查询订单（用于批量操作）
     */
    public List<Order> getOrdersByIds(List<Long> ids) {
        log.info("批量查询订单, ids={}", ids);
        simulateSlowQuery();
        return orderMapper.selectBatchIds(ids);
    }

    /**
     * 根据用户ID列表批量查询订单
     */
    public List<Order> getOrdersByUserIds(List<Long> userIds) {
        log.info("批量查询用户订单, userIds={}", userIds);
        simulateSlowQuery();
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(Order::getUserId, userIds);
        return orderMapper.selectList(wrapper);
    }

    /**
     * 更新订单（更新缓存）
     */
    @CachePut(
            cacheName = "order",
            keyExpr = "#order.id",
            cacheMode = CacheModeEnum.L1_L2
    )
    public Order updateOrder(Order order) {
        log.info("更新订单, order={}", order);
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);
        return order;
    }

    /**
     * 删除订单（清除缓存）
     */
    @CacheEvict(
            cacheName = "order",
            keyExpr = "#id"
    )
    public void deleteOrder(Long id) {
        log.info("删除订单, id={}", id);
        orderMapper.deleteById(id);
    }

    /**
     * 创建订单
     */
    public Order createOrder(Order order) {
        log.info("创建订单, order={}", order);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.insert(order);
        return order;
    }

    /**
     * 模拟慢查询
     */
    private void simulateSlowQuery() {
        try {
            Thread.sleep(300); // 模拟数据库查询延迟
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
