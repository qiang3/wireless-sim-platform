package com.chenmingqiang.wirelesssim.user.infrastructure;

import com.chenmingqiang.wirelesssim.user.domain.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// MyBatis说明：Spring会为该接口生成代理对象，方法由对应XML中的SQL实现。

@Mapper
/**
 * 教学注释：本文件为 infrastructure/UserMapper.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public interface UserMapper {

    UserAccount findByUsername(@Param("username") String username);

    int insert(UserAccount user);
}
