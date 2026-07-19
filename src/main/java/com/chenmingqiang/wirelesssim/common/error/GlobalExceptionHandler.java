package com.chenmingqiang.wirelesssim.common.error;

import com.chenmingqiang.wirelesssim.common.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局REST异常转换器，把Java异常统一转换成ApiResponse，避免每个Controller重复try/catch。
 */
// @RestControllerAdvice会拦截所有@RestController抛出的异常，并把返回值写成JSON响应。
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务层主动抛出的可预期异常。
     *
     * @param exception 包含HTTP状态和业务码的异常
     * @return 状态码与异常内容一致的错误响应
     */
    // @ExceptionHandler声明本方法只处理BusinessException及其子类。
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(ApiResponse.error(exception.getCode(), exception.getMessage()));
    }

    /**
     * 处理@RequestBody对象未通过Bean Validation的情况。
     *
     * @param exception Spring收集的请求体字段校验错误
     * @return 400及第一条字段错误，方便调用方快速定位
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException exception) {
        // 一个请求可能同时有多个错误；API第一版只返回第一条，保持响应简单稳定。
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("请求参数不合法");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("VALIDATION_ERROR", message));
    }

    /**
     * 处理Controller方法参数上的@Min、@Max等约束错误。
     *
     * @param exception 方法参数约束异常，例如分页参数越界
     * @return HTTP 400统一校验错误
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        // propertyPath会指出具体Controller方法和参数名称。
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .orElse("请求参数不合法");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("VALIDATION_ERROR", message));
    }

    /**
     * 处理JSON语法错误、字段类型不匹配或枚举值无法转换等反序列化问题。
     *
     * @param exception HTTP消息无法读取异常
     * @return 不暴露底层Jackson细节的400响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableMessage(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("INVALID_REQUEST_FORMAT", "请求JSON格式或字段值不合法"));
    }
}
