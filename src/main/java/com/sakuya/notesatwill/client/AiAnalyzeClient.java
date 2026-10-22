package com.sakuya.notesatwill.client;

import com.sakuya.notesatwill.entity.DTO.ai.PythonAnalyzeRequest;
import com.sakuya.notesatwill.entity.DTO.ai.PythonAnalyzeResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 消费点评接口的声明式客户端（{@code POST /ai/analyze}）。
 *
 * <p><b>★ 为什么必须单独一个客户端，而不是复用 {@link AiParseClient}</b>：
 * 超时属于底层 {@code ClientHttpRequestFactory}，<b>声明式接口无法按方法设超时</b>。
 * 解析（15s）与点评（15s）虽然当前取值相同，但<b>语义不同</b>：
 * <ul>
 *   <li>解析：用户在等结果，失败要明确报错</li>
 *   <li>点评：锦上添花，失败就静默降级</li>
 * </ul>
 * 分开后将来调优（比如点评降到 8s）不会牵连解析。
 * 装配成本只有 {@code AiClientConfig} 里的一行 Bean。
 *
 * @author sakuya
 */
@HttpExchange
public interface AiAnalyzeClient {

    /**
     * 调 Python 生成消费点评。
     *
     * @param request 已由 Java 聚合好的统计数字
     * @return 点评结果（失败时 {@code success=false}）
     */
    @PostExchange("/ai/analyze")
    PythonAnalyzeResponse analyze(@RequestBody PythonAnalyzeRequest request);
}
