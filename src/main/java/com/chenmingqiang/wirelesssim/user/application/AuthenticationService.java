package com.chenmingqiang.wirelesssim.user.application;

import com.chenmingqiang.wirelesssim.common.error.BusinessException;
import com.chenmingqiang.wirelesssim.security.JwtTokenService;
import com.chenmingqiang.wirelesssim.user.api.LoginRequest;
import com.chenmingqiang.wirelesssim.user.api.LoginResponse;
import com.chenmingqiang.wirelesssim.user.api.RegisterRequest;
import com.chenmingqiang.wirelesssim.user.api.RegisterResponse;
import com.chenmingqiang.wirelesssim.user.domain.UserAccount;
import com.chenmingqiang.wirelesssim.user.domain.UserRole;
import com.chenmingqiang.wirelesssim.user.domain.UserStatus;
import com.chenmingqiang.wirelesssim.user.infrastructure.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证应用服务：编排注册和登录用例，连接参数对象、数据库、密码编码器与 JWT 服务。
 */
// Spring说明：将该类注册为业务服务Bean，其他组件可通过构造方法注入它。
@Service
public class AuthenticationService {

    /** 用户表的数据访问接口。 */
    private final UserMapper userMapper;
    /** 对密码做 BCrypt 哈希，以及在登录时安全比对密码。 */
    private final PasswordEncoder passwordEncoder;
    /** 登录成功后签发 JWT。 */
    private final JwtTokenService tokenService;

    public AuthenticationService(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            JwtTokenService tokenService
    ) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional
    /** 注册新用户；用户名检查、密码哈希和插入数据库在同一事务中完成。 */
    public RegisterResponse register(RegisterRequest request) {
        String username = request.username().trim();
        if (userMapper.findByUsername(username) != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已被注册");
        }

        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            // 预检查后仍可能有两个并发请求同时插入，数据库唯一索引是最终防线。
            throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已被注册");
        }

        return new RegisterResponse(user.getId(), user.getUsername(), user.getRole().name());
    }

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional(readOnly = true)
    /** 校验账号、密码和账号状态；全部通过后签发 JWT，本方法不修改数据库。 */
    public LoginResponse login(LoginRequest request) {
        UserAccount user = userMapper.findByUsername(request.username().trim());
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // 账号不存在和密码错误返回同一提示，避免攻击者探测哪些用户名已注册。
            throw invalidCredentials();
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "USER_DISABLED", "用户已被禁用");
        }

        JwtTokenService.IssuedToken token = tokenService.issue(user);
        return new LoginResponse(
                token.value(),
                "Bearer",
                token.expiresInSeconds(),
                user.getId(),
                user.getUsername(),
                user.getRole().name()
        );
    }

    /** 集中构造“凭证无效”异常，确保不同失败分支返回一致的 HTTP 401。 */
    private BusinessException invalidCredentials() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
    }
}
