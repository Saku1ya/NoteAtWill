package com.sakuya.notesatwill.client;

import com.sakuya.notesatwill.entity.DTO.ai.PythonParseRequest;
import com.sakuya.notesatwill.entity.DTO.ai.PythonParseResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 账单解析接口的声明式客户端（{@code POST /ai/parse-bill}）。
 *
 * <p><b>★ 为什么按接口拆成多个客户端，而不是一个 {@code AiClient} 装所有方法</b>：
 * <b>超时属于底层的 {@code ClientHttpRequestFactory}，被整个客户端共享</b>，
 * 声明式接口无法按方法设超时。而本项目的三个 AI 接口超时需求差 10 倍：
 * <table border="1">
 *   <caption>各接口的目标超时</caption>
 *   <tr><th>接口</th><th>读超时</th><th>为什么</th></tr>
 *   <tr><td>{@code /ai/parse-bill}</td><td><b>15s</b></td>
 *       <td>用户点了「AI 记账」在等结果，给足时间</td></tr>
 *   <tr><td>{@code /ai/greeting}</td><td><b>3s</b></td>
 *       <td>首页一打开就要显示，宁可降级也不能白屏</td></tr>
 *   <tr><td>{@code /ai/chat}</td><td><b>30s</b></td>
 *       <td>要生成一整段回复，可以长一点</td></tr>
 * </table>
 * 如果塞进同一个客户端取统一值：取 15s 会拖慢首屏（用户盯着白屏 15 秒），
 * 取 3s 会让解析大面积超时。<b>所以"一个接口组一个客户端"是唯一可行的做法。</b>
 *
 * <p><b>★ 本接口抛的是 Spring 的 HTTP 异常，不是业务异常</b>：
 * <ul>
 *   <li>连接失败 / 读超时 → {@code ResourceAccessException}</li>
 *   <li>其它 HTTP 层错误 → {@code RestClientException}</li>
 * </ul>
 * 由 {@code AiService} 统一捕获并翻译成
 * {@code AI_SERVICE_UNAVAILABLE(5000)} / {@code AI_SERVICE_TIMEOUT(5001)}，
 * 让前端看到明确的提示而不是 500。
 *
 * @author sakuya
 */
@HttpExchange
public interface AiParseClient {

    /**
     * 调用 Python 的账单解析接口。
     *
     * <p>注意路径写全 {@code /ai/parse-bill}：Python 服务没有 context-path。
     *
     * @param request 文本 + 当前时间 + 分类候选 + 账户候选
     * @return 解析出的账单草稿（<b>不可信，需二次校验</b>）
     */
    @PostExchange("/ai/parse-bill")
    PythonParseResponse parseBill(@RequestBody PythonParseRequest request);
}
