package com.sakuya.notesatwill.common.result;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.sakuya.notesatwill.common.enums.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应体。
 *
 * <p>字段约定：
 * <ul>
 *   <li>{@code code} 业务状态码，0 表示成功；非 0 见 {@link ErrorCode}</li>
 *   <li>{@code message} 提示文案</li>
 *   <li>{@code data} 业务数据，成功时有值</li>
 * </ul>
 *
 * <p><b>关于 {@code @JsonInclude(NON_NULL)}</b>：为 null 的字段不会出现在 JSON 里。
 * 对前端的好处是成功响应不会带一堆无意义的 null，对 AI 服务的好处是返回结构更干净。
 *
 * <p><b>Jackson 版本坑（重要）</b>：Spring Boot 4 用的是 Jackson 3，
 * 它只把 {@code core} / {@code databind} 迁到了 {@code tools.jackson.*}，
 * <b>注解仍留在 {@code com.fasterxml.jackson.annotation.*}</b>。
 * 所以这里的 {@code @JsonInclude} 导入路径是 {@code com.fasterxml.jackson.annotation}，
 * 不要改成 {@code tools.jackson.annotation}——那个包根本不存在，会编译失败。
 *
 * <p>注意：本项目【不使用 HTTP 状态码表达业务结果】。所有业务响应一律 HTTP 200，
 * 用 {@code code} 区分成功与失败。这样前端拦截器只需判断一处，不必同时处理两层。
 * 真正的 HTTP 错误码（404/405/500 等）由全局异常处理器统一转成 Result 结构返回。
 *
 * @param <T> 业务数据类型
 * @author sakuya
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务状态码，200 表示成功 */
    private Integer code;

    /** 提示文案 */
    private String message;

    /** 业务数据 */
    private T data;

    // ======================== 成功 ========================

    /**
     * 成功，无数据。
     */
    public static <T> Result<T> success() {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    /**
     * 成功，带数据。
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    /**
     * 成功，自定义文案 + 数据。
     */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), message, data);
    }

    // ======================== 失败 ========================

    /**
     * 失败，使用错误码自带的文案。
     */
    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 失败，自定义文案（用于覆盖错误码默认文案，例如带上具体字段名）。
     */
    public static <T> Result<T> error(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    /**
     * 失败，完全自定义。
     */
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    // ======================== 判断 ========================

    /**
     * 是否成功。给需要判断返回值的调用方用（例如服务间调用后检查）。
     *
     * <p><b>⚠ 必须加 {@code @JsonIgnore}，不能省。</b>
     * 本类用了 Lombok 的 {@code @Data}，Jackson 会把形如 {@code isSuccess()}
     * 的方法识别成一个名为 {@code success} 的布尔属性并序列化出去，
     * 导致响应里凭空多出一个 {@code "success": true} 字段，破坏响应结构契约。
     * 加上 {@code @JsonIgnore} 后彻底排除。
     */
    @JsonIgnore
    public boolean isSuccess() {
        return ErrorCode.SUCCESS.getCode().equals(this.code);
    }
}
