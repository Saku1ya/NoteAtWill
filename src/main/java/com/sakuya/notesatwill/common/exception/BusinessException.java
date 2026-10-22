package com.sakuya.notesatwill.common.exception;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import lombok.Getter;

import java.io.Serial;

/**
 * 业务异常。
 *
 * <p>凡是「可预期的业务失败」都抛这个（例如账户余额不足、分类重名），
 * 由 {@code GlobalExceptionHandler} 统一捕获并转成 {@code Result} 返回。
 *
 * <p><b>不要用它包装不可预期的错误</b>（数据库连接断了、空指针），
 * 那类异常应该让它抛出去，走兜底分支并打错误日志，便于排查。
 *
 * <p>继承 {@link RuntimeException} 而不是 Exception，是为了避免到处写 try-catch
 * ——业务校验失败本来就是"快速失败"的场景，没有必须强制处理的资源。
 *
 * @author sakuya
 */
@Getter
public class BusinessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 错误码 */
    private final Integer code;

    /**
     * 用错误码构造，文案取错误码自带值。
     */
    public BusinessException(ErrorCode errorCode) {
        // 传入 errorCode 作为 cause 之外的 message，
        // 保证日志里能看到不算空的异常信息
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 用错误码构造，但自定义文案。
     *
     * <p>典型用法：{@code new BusinessException(ErrorCode.PARAM_ERROR, "分类名不能为空")}，
     * 这样前端既能拿到统一错误码，又能看到具体是哪出了问题。
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    /**
     * 自定义错误码与文案。
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 带原因构造，用于包装底层异常（保留原始堆栈便于排查）。
     */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
    }

    // ======================== 静态工厂：让抛异常的代码更短 ========================

    /**
     * 抛业务异常。
     *
     * <p>用法：{@code throw BusinessException.of(ErrorCode.ACCOUNT_NOT_FOUND);}
     * 比 {@code new BusinessException(...)} 少写几个字符，读起来也更像"抛出"。
     */
    public static BusinessException of(ErrorCode errorCode) {
        return new BusinessException(errorCode);
    }

    public static BusinessException of(ErrorCode errorCode, String message) {
        return new BusinessException(errorCode, message);
    }

    /**
     * 条件成立时抛出异常。用于替代大段 if-throw。
     *
     * <p>用法：{@code BusinessException.throwIf(account.getBalance().compareTo(amount) < 0, ErrorCode.ACCOUNT_NOT_ENOUGH_BALANCE);}
     */
    public static void throwIf(boolean condition, ErrorCode errorCode) {
        if (condition) {
            throw new BusinessException(errorCode);
        }
    }

    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        if (condition) {
            throw new BusinessException(errorCode, message);
        }
    }
}
