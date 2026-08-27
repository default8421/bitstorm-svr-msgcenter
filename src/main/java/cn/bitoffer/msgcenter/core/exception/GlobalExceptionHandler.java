package cn.bitoffer.msgcenter.core.exception;

import cn.bitoffer.common.model.ResponseEntity;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 *
 * <p>两条业界通行的规则:
 * <ol>
 *   <li>HTTP 状态码要表达真实语义(400/404/429/500)，不能所有响应都是 200、只靠 body 里的
 *       {@code code} 字段区分成功失败 —— 那是老式 RPC 风格，不是 REST。</li>
 *   <li>日志级别要按"谁的锅"区分:客户端传错参数、业务规则不允许，这些是正常业务流程的一部分，
 *       用 {@code WARN}；只有系统自身出了意外(数据库挂了、下游超时、未知异常)才用 {@code ERROR}。
 *       否则 ERROR 日志会被大量"用户传错参数"淹没，真正的系统故障反而被埋没，线上告警形同虚设。</li>
 * </ol>
 *
 * @author LQH
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** Bean Validation(@Valid/@Validated) 校验失败：参数错误，客户端的锅，400 + WARN。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public org.springframework.http.ResponseEntity<ResponseEntity<?>> validationExceptionHandler(
            MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        ResponseEntity<?> body = ResponseEntity.failBusinessException(ErrorCode.PARAMS_ERROR.getCode(),
                message);
        return org.springframework.http.ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 平台过载：429 + {@code Retry-After}。带上 Retry-After 才能让调用方按同一个节奏退避，
     * 否则各家 SDK 会用自己的固定间隔重试，反而在同一时刻再次形成尖峰。
     */
    @ExceptionHandler(OverloadException.class)
    public org.springframework.http.ResponseEntity<ResponseEntity<?>> overloadExceptionHandler(
            OverloadException e) {
        log.warn("平台过载拒绝请求: {}", e.getMessage());
        ResponseEntity<?> body = ResponseEntity.failBusinessException(e.getCode(), e.getMessage());
        return org.springframework.http.ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(body);
    }

    @ExceptionHandler(BusinessException.class)
    public org.springframework.http.ResponseEntity<ResponseEntity<?>> businessExceptionHandler(
            BusinessException e) {
        HttpStatus status = statusForBusinessCode(e.getCode());
        if (status.is5xxServerError()) {
            // 服务端自身的问题(比如入队失败)，需要被当成系统故障排查，保留堆栈。
            log.error("BusinessException code={} msg={}", e.getCode(), e.getMessage(), e);
        } else {
            // 客户端参数/业务状态问题(模板不存在、限流、状态不对)，是正常业务分支，不是系统故障。
            log.warn("BusinessException code={} msg={}", e.getCode(), e.getMessage());
        }
        ResponseEntity<?> body = ResponseEntity.failBusinessException(e.getCode(), e.getMessage());
        return org.springframework.http.ResponseEntity.status(status).body(body);
    }

    /** 兜底：任何没被预料到的异常，一律当系统故障处理，500 + ERROR + 完整堆栈。 */
    @ExceptionHandler(RuntimeException.class)
    public org.springframework.http.ResponseEntity<ResponseEntity<?>> runtimeExceptionHandler(
            RuntimeException e) {
        log.error("未捕获的 RuntimeException", e);
        ResponseEntity<?> body = ResponseEntity.fail();
        return org.springframework.http.ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /** ErrorCode 用 40000/50000 分段区分客户端/服务端错误(见 ErrorCode 定义)，限流单独映射到 429。 */
    private static HttpStatus statusForBusinessCode(int code) {
        if (code == ErrorCode.RateLimit_ERROR.getCode()) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        if (code >= 40000 && code < 50000) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
