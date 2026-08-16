package io.github.nitouge.cache.test.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.nitouge.cache.test.entity.Stock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 库存 Mapper
 */
@Mapper
public interface StockMapper extends BaseMapper<Stock> {

    /**
     * 根据商品ID查询库存（从库查询）
     *
     * @param productId 商品ID
     * @return 库存信息
     */
    @Select("SELECT * FROM t_stock WHERE product_id = #{productId}")
    Stock selectByProductId(@Param("productId") Long productId);

    /**
     * 乐观锁更新库存数量
     *
     * @param productId 商品ID
     * @param quantity  新的库存数量
     * @param version   当前版本号
     * @return 更新的行数
     */
    @Update("UPDATE t_stock SET quantity = #{quantity}, version = version + 1 " +
            "WHERE product_id = #{productId} AND version = #{version}")
    int updateStockWithVersion(@Param("productId") Long productId,
                                @Param("quantity") Integer quantity,
                                @Param("version") Long version);

    /**
     * 扣减库存（乐观锁）
     *
     * @param productId 商品ID
     * @param decrement 扣减数量
     * @param version   当前版本号
     * @return 更新的行数
     */
    @Update("UPDATE t_stock SET quantity = quantity - #{decrement}, version = version + 1 " +
            "WHERE product_id = #{productId} AND version = #{version} AND quantity >= #{decrement}")
    int decrementStock(@Param("productId") Long productId,
                       @Param("decrement") Integer decrement,
                       @Param("version") Long version);
}
