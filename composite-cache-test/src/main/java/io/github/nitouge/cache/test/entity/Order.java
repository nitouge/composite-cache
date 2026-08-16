package io.github.nitouge.cache.test.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单实体
 * 
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_order")
public class Order implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 订单编号
     */
    private String orderNo;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 商品ID
     */
    private Long productId;

    /**
     * 商品数量
     */
    private Integer quantity;

    /**
     * 订单总金额
     */
    private BigDecimal totalAmount;

    /**
     * 订单状态：0-待支付，1-已支付，2-已发货，3-已完成，4-已取消
     */
    private Integer status;

    /**
     * 收货地址
     */
    private String shippingAddress;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 关联的用户信息（非数据库字段）
     */
    @TableField(exist = false)
    private User user;

    /**
     * 关联的商品信息（非数据库字段）
     */
    @TableField(exist = false)
    private Product product;

    /**
     * 关联的商品列表（用于多商品订单场景，非数据库字段）
     */
    @TableField(exist = false)
    private List<Product> products;
}
