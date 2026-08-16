package io.github.nitouge.cache.test.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存实体
 */
@Data
@TableName("t_stock")
public class Stock implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 库存ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 商品ID
     */
    private Long productId;

    /**
     * 库存数量
     */
    private Integer quantity;

    /**
     * 版本号（乐观锁）
     */
    @Version
    private Long version;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
