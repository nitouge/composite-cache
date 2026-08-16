package io.github.nitouge.cache.test.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.nitouge.cache.test.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商品 Mapper
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 根据分类查询商品列表
     */
    @Select("SELECT * FROM t_product WHERE category = #{category} AND status = 1 ORDER BY create_time DESC")
    List<Product> selectByCategory(@Param("category") String category);

    /**
     * 根据ID查询商品（用于测试缓存）
     */
    @Select("SELECT * FROM t_product WHERE id = #{id}")
    Product selectProductById(@Param("id") Long id);
}
