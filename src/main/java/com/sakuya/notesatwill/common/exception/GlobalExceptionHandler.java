package com.sakuya.notesatwill.common.exception;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.result.Result;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>设计原则：
 * <ol>
 *   <li><b>所有异常都转成 {@link Result} 结构</b>，前端只需要处理一种响应格式，
 *       不用同时解析 Spring 默认的错误 JSON 和自己的 Result。</li>
 *   <li><b>可预期的业务失败不打印堆栈</b>（BusinessException、参数校验），
 *       只记一行 warn——否则日志会被用户的正常误操作刷满。</li>
 *   <li><b>不可预期的异常必须打完整堆栈</b>，并且<b>不把内部细节返回给前端</b>，
 *       避免泄漏实现信息。</li>
 * </ol>
 *
 * <p><b>为什么类上是 {@code @RestControllerAdvice} 而不是 {@code @ControllerAdvice}</b>：
 * 前者等于 {@code @ControllerAdvice} + {@code @ResponseBody}，
 * 能直接把返回值序列化成 JSON。用后者的话每个方法都要加 {@code @ResponseBody}。
 *
 * @author sakuya
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ========================================================================
    // 一、业务异常：可预期，只记 warn，不打堆栈
    // ========================================================================

    /**
     * 业务异常。这是最常走的分支。
     *
     * <p>注意这里【不用】{@code @ResponseStatus}：业务失败仍然返回 HTTP 200，
     * 用 Result.code 表达错误。这样前端拦截器只需判断 code 一处。
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常 | code={} | message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    // ========================================================================
    // 二、参数校验异常：全部统一成错误码 1000 / 1001 / 1002
    // ========================================================================

    /**
     * {@code @RequestBody} 上的 {@code @Valid} 校验失败（JSON 请求体）。
     *
     * <p>只取第一条错误信息返回。原因是前端表单通常逐字段提示，
     * 一次返回全部错误反而不好展示；如果将来要做字段级提示，
     * 可以换成返回 Map&lt;字段名, 错误信息&gt;。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败（RequestBody） | {}", message);
        return Result.error(ErrorCode.PARAM_ERROR, message);
    }

    /**
     * 表单 / Query 参数绑定校验失败。
     *
     * <p>与上面那个的区别：{@code MethodArgumentNotValidException} 用于 JSON body，
     * 这个用于 {@code @ModelAttribute}（表单）绑定。两者是不同异常，
     * 但都继承自 {@code BindException}，这里分开写是为了让日志能区分来源。
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        log.warn("参数绑定失败（Form/Query） | {}", message);
        return Result.error(ErrorCode.PARAM_ERROR, message);
    }

    /**
     * 方法参数上的 {@code @Validated} 校验失败（如 {@code @PathVariable @Min(1)}）。
     *
     * <p>这类校验不是通过 BindingResult 传递的，而是直接抛异常，所以要单独处理。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数约束校验失败 | {}", message);
        return Result.error(ErrorCode.PARAM_ERROR, message);
    }

    /**
     * 缺少必填的请求参数。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParameter(MissingServletRequestParameterException e) {
        String message = "缺少必要参数：" + e.getParameterName();
        log.warn("{} | type={}", message, e.getParameterType());
        return Result.error(ErrorCode.PARAM_MISSING, message);
    }

    /**
     * 参数类型不匹配，例如把 {@code /api/bills/abc} 里的 abc 往 Long 上转。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String message = "参数 " + e.getName() + " 类型不正确";
        log.warn("{} | value={}", message, e.getValue());
        return Result.error(ErrorCode.PARAM_TYPE_ERROR, message);
    }

    /**
     * 请求体无法解析（JSON 格式错误、字段类型对不上）。
     *
     * <p>注意：这里【不】把原始异常信息返回给前端，因为它会带上类的全限定名，
     * 属于实现细节。只给一句通用提示，详细信息打日志。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败 | {}", e.getMessage());
        return Result.error(ErrorCode.PARAM_ERROR, "请求体格式不正确，请检查 JSON 格式");
    }

    // ========================================================================
    // 三、路由与静态资源异常
    // ========================================================================

    /**
     * 请求路径不存在（404）。
     *
     * <p>Spring Boot 3.2+ 起，未匹配到 handler 时抛的是 {@code NoResourceFoundException}
     * 而不是 {@code NoHandlerFoundException}（后者需要额外配置 spring.mvc.throw-exception-if-no-handler-found）。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("请求路径不存在 | {}", e.getResourcePath());
        return Result.error(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /**
     * 请求方法不支持，例如用 GET 请求一个只接受 POST 的接口（405）。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        String message = "请求方法 " + e.getMethod() + " 不被支持";
        log.warn("{} | 支持的方法={}", message, e.getSupportedHttpMethods());
        return Result.error(ErrorCode.REQUEST_METHOD_NOT_SUPPORTED, message);
    }

    // ========================================================================
    // 四、兜底：不可预期的异常
    // ========================================================================

    /**
     * 兜底处理。
     *
     * <p>两点纪律：
     * <ol>
     *   <li>必须用 {@code log.error} 打完整堆栈——这是排查线上问题的唯一线索；</li>
     *   <li>返回给前端的只能是一句通用文案，绝不能把 {@code e.getMessage()} 透出去，
     *       因为它可能包含 SQL 片段、文件路径、类名等敏感信息。</li>
     * </ol>
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常，未预期的错误", e);
        return Result.error(ErrorCode.SYSTEM_ERROR);
    }

    // ========================================================================
    // 私有工具方法
    // ========================================================================

    /**
     * 把字段错误格式化成「字段名: 提示」。
     *
     * <p>这样前端能直接拿到可读的中文提示，不用自己再映射字段名。
     */
    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}
