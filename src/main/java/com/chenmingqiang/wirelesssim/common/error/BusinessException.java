package com.chenmingqiang.wirelesssim.common.error;

import org.springframework.http.HttpStatus;

/**
 * 可预期的业务异常，例如资源不存在、状态冲突或幂等键误用。
 *
 * <p>它继承RuntimeException，因此在事务方法中抛出时，Spring默认回滚事务；
 * GlobalExceptionHandler会把它转换成统一HTTP错误响应。</p>
 */
public class BusinessException extends RuntimeException {

    /** 返回给客户端的HTTP状态，例如404或409。 */
    private final HttpStatus status;

    /** 稳定业务错误码，供前端按类型处理，而不是解析中文消息。 */
    private final String code;

    /**
     * 创建业务异常。
     *
     * @param status HTTP状态
     * @param code 业务错误码
     * @param message 可读错误信息，同时保存为RuntimeException的message
     */
    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** @return 应返回的HTTP状态 */
    public HttpStatus getStatus() {
        return status;
    }

    /** @return 稳定业务错误码 */
    public String getCode() {
        return code;
    }
}
