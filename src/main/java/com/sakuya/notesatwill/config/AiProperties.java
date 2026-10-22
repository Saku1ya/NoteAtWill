package com.sakuya.notesatwill.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 服务（Python FastAPI）的配置。
 *
 * <p>对应 {@code application.yaml} 里的：
 * <pre>
 * notes-at-will:
 *   ai:
 *     base-url: http://127.0.0.1:8000
 *     timeouts:
 *       parse-bill: 15000
 *       greeting: 3000
 *       chat: 30000
 * </pre>
 *
 * <p><b>★ 为什么超时要按接口分三个，而不是一个统一值</b>：
 * <ul>
 *   <li>{@code /parse-bill}（15s）—— 用户点了「AI 记账」在等结果，给足时间</li>
 *   <li>{@code /analyze}（15s）—— 生成 100~200 字点评，比解析慢但不该太久</li>
 *   <li>{@code /greeting}（3s）—— 首页打开就要显示，宁可降级也不能白屏</li>
 *   <li>{@code /chat}（30s）—— 要生成一段话，可以长一点</li>
 * </ul>
 * 用统一值必然错：取 15s 会拖慢首屏，取 3s 会让解析大面积超时。
 *
 * @param baseUrl  Python 服务地址
 * @param timeouts 各接口的超时（毫秒）
 * @author sakuya
 */
@ConfigurationProperties(prefix = "notes-at-will.ai")
public record AiProperties(String baseUrl, Timeouts timeouts) {

    /**
     * 各接口超时（毫秒）。
     *
     * <p>kebab-case 的 {@code parse-bill} 会被自动绑定到 {@code parseBill}（宽松绑定）。
     *
     * @param parseBill 账单解析超时
     * @param greeting  每日问候超时
     * @param chat      对话回复超时
     * @param analyze   消费点评超时
     */
    public record Timeouts(long parseBill, long greeting, long chat, long analyze) {
    }
}
