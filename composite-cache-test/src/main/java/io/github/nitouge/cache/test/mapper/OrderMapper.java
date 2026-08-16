package io.github.nitouge.cache.test.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.nitouge.cache.test.entity.Order;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单Mapper
 * 
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
