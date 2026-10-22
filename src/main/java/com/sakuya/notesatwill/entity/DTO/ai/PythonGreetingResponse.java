package com.sakuya.notesatwill.entity.DTO.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Python {@code POST /ai/greeting} 的响应。
 *
 * <p><b>★ 与其它两个 AI 接口同构</b>：永远 200 + {@code success} 标志。
 *
 * <p><b>★ {@code success=false} 时 Java 用<b>模板问候</b>兜底</b>：
 * 这是首屏内容，<b>宁可显示模板也不能白屏</b>
 * （模板里也带真实数字，体验不至于太差）。
 *
 * @param success  是否成功生成
 * @param greeting 问候语
 * @param error    失败原因
 * @author sakuya
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PythonGreetingResponse(
        Boolean success,
        String greeting,
        String error) {
}
