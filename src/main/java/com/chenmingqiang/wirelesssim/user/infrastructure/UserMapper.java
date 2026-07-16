package com.chenmingqiang.wirelesssim.user.infrastructure;

import com.chenmingqiang.wirelesssim.user.domain.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {

    UserAccount findByUsername(@Param("username") String username);

    int insert(UserAccount user);
}
