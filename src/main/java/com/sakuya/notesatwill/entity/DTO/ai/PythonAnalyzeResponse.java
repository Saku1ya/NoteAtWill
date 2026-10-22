package com.sakuya.notesatwill.entity.DTO.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Python {@code POST /ai/analyze} 的响应。
 *
 * <p><b>★ 与 {@code PythonParseResponse} 同构</b>：永远 200 + {@code success} 标志，
 * 而不是用 HTTP 状态码表达业务失败 —— 这样 Java 只需看一个布尔值决定是否展示点评。
 *
 * <p><b>★ {@code comment} 可能为 null</b>（{@code success=false} 时）。
 * <b>点评是"锦上添花"，失败不该让统计页报错</b> ——
 * 上层会把它降级成 {@code data: null}，前端不显示这一块即可。
 *
 * @param success 是否成功生成
 * @param comment 点评正文（100~200 字）
 * @param error   失败原因
 * @author sakuya
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PythonAnalyzeResponse(
        Boolean success,
        String comment,
        String error) {
}
