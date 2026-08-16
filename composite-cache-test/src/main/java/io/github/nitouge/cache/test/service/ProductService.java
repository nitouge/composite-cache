package io.github.nitouge.cache.test.service;

import io.github.nitouge.cache.annotation.CacheAble;
import io.github.nitouge.cache.annotation.CacheEvict;
import io.github.nitouge.cache.annotation.CachePut;
import io.github.nitouge.cache.annotation.Cache_L1;
import io.github.nitouge.cache.annotation.Cache_L2;
import io.github.nitouge.cache.core.consts.enums.CacheModeEnum;
import io.github.nitouge.cache.test.entity.Product;
import io.github.nitouge.cache.test.mapper.ProductMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品服务
 * 
 * <p>演示完整的缓存配置：
 * <ul>
 *   <li>L1 缓存（Caffeine）：快速访问，适合热点数据</li>
 *   <li>L2 缓存（Redis）：持久化，适合共享数据</li>
 *   <li>缓存更新、删除操作</li>
 * </ul>
 */
@Slf4j
@Service
public class ProductService {

    @Autowired
    private ProductMapper productMapper;

    /**
     * 根据ID查询商品（完整的 L1+L2 缓存配置）
     * 
     * <p>缓存配置说明（生产环境推荐配置）：
     * <ul>
     *   <li>cacheName: product - 缓存名称</li>
     *   <li>cacheMode: L1_L2 - 使用一级和二级缓存</li>
     *   <li>L1 (Caffeine): 60秒过期，最大1000条，初始容量100</li>
     *   <li>L2 (Redis): 5分钟过期</li>
     * </ul>
     * 
     * @param id 商品ID
     * @return 商品信息
     */
    @CacheAble(
        cacheName = "product",
        keyExpr = "#id",                      // 使用方法参数 id 作为缓存 key
        cacheMode = CacheModeEnum.L1_L2,
        cacheL1 = @Cache_L1(
            TTL = 60,                     // L1 缓存 60 秒过期
            timeUnit = java.util.concurrent.TimeUnit.SECONDS,
            maximumSize = 1000,           // 最大缓存 1000 条
            initialCapacity = 100         // 初始容量 100
        ),
        cacheL2 = @Cache_L2(
            TTL = 300,                    // L2 缓存 5 分钟过期
            timeUnit = java.util.concurrent.TimeUnit.SECONDS
        )
    )
    public Product getProductById(Long id) {
        log.info("从数据库查询商品, id={}", id);
        return productMapper.selectProductById(id);
    }

    /**
     * 根据分类查询商品列表（只使用 L1 缓存，短时间缓存）
     * 
     * <p>适用场景：频繁变化的列表数据，只需要短时间缓存
     * 
     * @param category 商品分类
     * @return 商品列表
     */
    @CacheAble(
        cacheName = "product:category",
        keyExpr = "#category",                // 使用分类作为缓存 key
        cacheMode = CacheModeEnum.L1,
        cacheL1 = @Cache_L1(
            TTL = 30,                     // L1 缓存 30 秒过期
            timeUnit = java.util.concurrent.TimeUnit.SECONDS,
            maximumSize = 100
        )
    )
    public List<Product> getProductsByCategory(String category) {
        log.info("从数据库查询商品列表, category={}", category);
        return productMapper.selectByCategory(category);
    }

    /**
     * 更新商品价格（使用 @CachePut 更新缓存）
     * 
     * <p>@CachePut 会在方法执行后更新缓存，确保缓存与数据库一致
     * 
     * @param id 商品ID
     * @param newPrice 新价格
     * @return 更新后的商品信息
     */
    @CachePut(
        cacheName = "product",
        keyExpr = "#id",                      // 使用 id 作为缓存 key
        cacheMode = CacheModeEnum.L1_L2,
        cacheL1 = @Cache_L1(
            TTL = 60,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS,
            maximumSize = 1000,
            initialCapacity = 100
        ),
        cacheL2 = @Cache_L2(
            TTL = 300,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS
        )
    )
    public Product updateProductPrice(Long id, BigDecimal newPrice) {
        log.info("更新商品价格, id={}, newPrice={}", id, newPrice);
        Product product = productMapper.selectProductById(id);
        if (product != null) {
            product.setPrice(newPrice);
            productMapper.updateById(product);
        }
        return product;
    }

    /**
     * 更新商品库存（使用 @CachePut 更新缓存）
     * 
     * @param id 商品ID
     * @param stock 新库存
     * @return 更新后的商品信息
     */
    @CachePut(
        cacheName = "product",
        keyExpr = "#id",                      // 使用 id 作为缓存 key
        cacheMode = CacheModeEnum.L1_L2,
        cacheL1 = @Cache_L1(TTL = 60, timeUnit = java.util.concurrent.TimeUnit.SECONDS),
        cacheL2 = @Cache_L2(TTL = 300, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
    )
    public Product updateProductStock(Long id, Integer stock) {
        log.info("更新商品库存, id={}, stock={}", id, stock);
        Product product = productMapper.selectProductById(id);
        if (product != null) {
            product.setStock(stock);
            productMapper.updateById(product);
        }
        return product;
    }

    /**
     * 删除商品（使用 @CacheEvict 清除缓存）
     * 
     * <p>@CacheEvict 会在方法执行后清除缓存
     * 
     * @param id 商品ID
     */
    @CacheEvict(
        cacheName = "product",
        keyExpr = "#id",                      // 使用 id 作为缓存 key
        cacheMode = CacheModeEnum.L1_L2
    )
    public void deleteProduct(Long id) {
        log.info("删除商品, id={}", id);
        productMapper.deleteById(id);
    }

    /**
     * 清空分类缓存
     * 
     * @param category 商品分类
     */
    @CacheEvict(
        cacheName = "product:category",
        keyExpr = "#category",                // 使用分类作为缓存 key
        cacheMode = CacheModeEnum.L1
    )
    public void clearCategoryCache(String category) {
        log.info("清空分类缓存, category={}", category);
    }

    /**
     * 批量查询商品（用于批量操作）
     * 
     * @param ids 商品ID列表
     * @return 商品列表
     */
    public List<Product> getProductsByIds(List<Long> ids) {
        log.info("批量查询商品, ids={}", ids);
        simulateSlowQuery();
        return productMapper.selectBatchIds(ids);
    }

    /**
     * 直接从数据库查询（不使用缓存）
     * 
     * @param id 商品ID
     * @return 商品信息
     */
    public Product getProductByIdNoCache(Long id) {
        log.info("从数据库查询商品（不使用缓存）, id={}", id);
        return productMapper.selectProductById(id);
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
