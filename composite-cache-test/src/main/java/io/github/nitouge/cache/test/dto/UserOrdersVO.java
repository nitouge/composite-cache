package io.github.nitouge.cache.test.dto;

import io.github.nitouge.cache.test.entity.Order;
import io.github.nitouge.cache.test.entity.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 用户 + 订单 组合视图（级联加载的安全产物）。
 *
 * <p>由 {@code CacheTemplate.batchGetOrLoadCombined} 的 assembler 组合而成：它是一个<b>新对象</b>，
 * 不修改被缓存的 {@link User}/{@link Order}，因此不会污染 L1 缓存（对比直接给 user/order 挂关联的副作用写法）。
 *
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserOrdersVO {

    private User user;

    private List<Order> orders;

    /** 派生字段：订单数（仅用于展示）。 */
    public int getOrderCount() {
        return orders == null ? 0 : orders.size();
    }
}
