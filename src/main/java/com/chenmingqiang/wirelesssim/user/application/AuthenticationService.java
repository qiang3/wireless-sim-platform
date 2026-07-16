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

@Service
public class AuthenticationService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
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

    @Transactional
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
            throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已被注册");
        }

        return new RegisterResponse(user.getId(), user.getUsername(), user.getRole().name());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        UserAccount user = userMapper.findByUsername(request.username().trim());
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
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

    private BusinessException invalidCredentials() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
    }
}
