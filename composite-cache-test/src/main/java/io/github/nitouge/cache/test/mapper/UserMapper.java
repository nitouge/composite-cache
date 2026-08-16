package io.github.nitouge.cache.test.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.nitouge.cache.test.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper
 *
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
    
}
