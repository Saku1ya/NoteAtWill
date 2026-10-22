package com.sakuya.notesatwill.client;

import com.sakuya.notesatwill.entity.DTO.ai.PythonGreetingRequest;
import com.sakuya.notesatwill.entity.DTO.ai.PythonGreetingResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 每日问候接口的声明式客户端（{@code POST /ai/greeting}）。
 *
 * <p><b>★★ 读超时只有 3 秒</b>（{@code notes-at-will.ai.timeouts.greeting}），
 * 远小于解析的 15s、点评的 15s、对话的 30s。
 *
 * <p><b>为什么必须这么短</b>：这是<b>首屏接口</b>，用户打开页面就在等。
 * 让用户盯着白屏 15 秒，远不如 3 秒后用模板问候 ——
 * <b>有内容的模板 > 空白等待 > 转圈 15 秒</b>。
 *
 * <p>这也正是「一个接口组一个客户端」的原因：
 * 超时属于底层 {@code ClientHttpRequestFactory}，声明式接口无法按方法设超时。
 *
 * @author sakuya
 */
@HttpExchange
public interface AiGreetingClient {

    /**
     * 调 Python 生成每日问候。
     *
     * @param request 时段 + 本月数字（均由 Java 算好）
     * @return 问候结果；失败时 {@code success=false}
     */
    @PostExchange("/ai/greeting")
    PythonGreetingResponse greeting(@RequestBody PythonGreetingRequest request);
}
