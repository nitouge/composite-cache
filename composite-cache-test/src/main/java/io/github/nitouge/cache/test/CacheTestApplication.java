package io.github.nitouge.cache.test;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 复合缓存测试应用
 *
 * <p>测试应用，演示 Composite Cache 的使用
 *
 */
@SpringBootApplication
@MapperScan("io.github.nitouge.cache.test.mapper")
public class CacheTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(CacheTestApplication.class, args);
    }

}
