package io.github.nitouge.cache.test.controller;

import io.github.nitouge.cache.test.entity.Product;
import io.github.nitouge.cache.test.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品控制器
 *
 * <p>测试接口说明：
 * <ul>
 *   <li>GET /product/{id} - 查询商品（使用 L1+L2 缓存）</li>
 *   <li>GET /product/category/{category} - 查询分类商品（使用 L1 缓存）</li>
 *   <li>PUT /product/{id}/price - 更新商品价格（更新缓存）</li>
 *   <li>PUT /product/{id}/stock - 更新商品库存（更新缓存）</li>
 *   <li>DELETE /product/{id} - 删除商品（清除缓存）</li>
 *   <li>GET /product/{id}/nocache - 查询商品（不使用缓存）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/product")
public class ProductController {

    @Autowired
    private ProductService productService;

    /**
     * 根据ID查询商品（使用缓存）
     * 
     * <p>测试步骤：
     * <ol>
     *   <li>第一次访问：从数据库加载，缓存到 L1 和 L2</li>
     *   <li>第二次访问：从 L1 缓存返回（极快，<1ms）</li>
     *   <li>等待 60 秒后访问：L1 过期，从 L2 返回（快，几ms）</li>
     *   <li>等待 5 分钟后访问：L2 过期，从数据库重新加载</li>
     * </ol>
     * 
     * @param id 商品ID
     * @return 商品信息
     */
    @GetMapping("/{id}")
    public Map<String, Object> getProductById(@PathVariable Long id) {
        long startTime = System.currentTimeMillis();
        Product product = productService.getProductById(id);
        long endTime = System.currentTimeMillis();
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", product);
        result.put("responseTime", (endTime - startTime) + "ms");
        result.put("message", "查询成功");
        return result;
    }

    /**
     * 根据分类查询商品列表（使用 L1 缓存）
     * 
     * @param category 商品分类
     * @return 商品列表
     */
    @GetMapping("/category/{category}")
    public Map<String, Object> getProductsByCategory(@PathVariable String category) {
        long startTime = System.currentTimeMillis();
        List<Product> products = productService.getProductsByCategory(category);
        long endTime = System.currentTimeMillis();
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", products);
        result.put("count", products.size());
        result.put("responseTime", (endTime - startTime) + "ms");
        result.put("message", "查询成功");
        return result;
    }

    /**
     * 更新商品价格（更新缓存）
     * 
     * <p>测试步骤：
     * <ol>
     *   <li>先查询商品，缓存数据</li>
     *   <li>更新价格，缓存会自动更新</li>
     *   <li>再次查询，返回更新后的数据（从缓存）</li>
     * </ol>
     * 
     * @param id 商品ID
     * @param price 新价格
     * @return 更新后的商品信息
     */
    @PutMapping("/{id}/price")
    public Map<String, Object> updateProductPrice(@PathVariable Long id, @RequestParam BigDecimal price) {
        Product product = productService.updateProductPrice(id, price);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", product);
        result.put("message", "价格更新成功，缓存已同步");
        return result;
    }

    /**
     * 更新商品库存（更新缓存）
     * 
     * @param id 商品ID
     * @param stock 新库存
     * @return 更新后的商品信息
     */
    @PutMapping("/{id}/stock")
    public Map<String, Object> updateProductStock(@PathVariable Long id, @RequestParam Integer stock) {
        Product product = productService.updateProductStock(id, stock);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", product);
        result.put("message", "库存更新成功，缓存已同步");
        return result;
    }

    /**
     * 删除商品（清除缓存）
     * 
     * <p>测试步骤：
     * <ol>
     *   <li>先查询商品，缓存数据</li>
     *   <li>删除商品，缓存会自动清除</li>
     *   <li>再次查询，返回 null（缓存已清除）</li>
     * </ol>
     * 
     * @param id 商品ID
     * @return 删除结果
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "商品删除成功，缓存已清除");
        return result;
    }

    /**
     * 查询商品（不使用缓存）
     * 
     * <p>用于对比缓存和非缓存的性能差异
     * 
     * @param id 商品ID
     * @return 商品信息
     */
    @GetMapping("/{id}/nocache")
    public Map<String, Object> getProductByIdNoCache(@PathVariable Long id) {
        long startTime = System.currentTimeMillis();
        Product product = productService.getProductByIdNoCache(id);
        long endTime = System.currentTimeMillis();
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", product);
        result.put("responseTime", (endTime - startTime) + "ms");
        result.put("message", "查询成功（不使用缓存）");
        return result;
    }

    /**
     * 缓存测试说明
     * 
     * @return 测试说明
     */
    @GetMapping("/test-guide")
    public Map<String, Object> testGuide() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "商品缓存测试指南");
        
        Map<String, String> steps = new HashMap<>();
        steps.put("1", "GET /product/1 - 第一次查询，从数据库加载（慢）");
        steps.put("2", "GET /product/1 - 第二次查询，从 L1 缓存返回（极快，<1ms）");
        steps.put("3", "GET /product/1/nocache - 不使用缓存查询，对比性能差异");
        steps.put("4", "PUT /product/1/price?price=8999 - 更新价格，缓存自动更新");
        steps.put("5", "GET /product/1 - 查询商品，返回更新后的价格（从缓存）");
        steps.put("6", "GET /product/category/手机 - 查询分类商品（L1 缓存 1 分钟）");
        steps.put("7", "DELETE /product/1 - 删除商品，缓存自动清除");
        steps.put("8", "GET /product/1 - 查询已删除的商品，返回 null");
        
        result.put("testSteps", steps);
        
        Map<String, String> cacheConfig = new HashMap<>();
        cacheConfig.put("product", "L1: 60秒, L2: 5分钟（生产环境推荐配置）");
        cacheConfig.put("product:category", "L1: 30秒（列表数据短时间缓存）");
        result.put("cacheConfig", cacheConfig);
        
        return result;
    }
}
