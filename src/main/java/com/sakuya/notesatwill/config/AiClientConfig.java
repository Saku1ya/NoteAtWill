package com.sakuya.notesatwill.config;

import com.sakuya.notesatwill.client.AiAnalyzeClient;
import com.sakuya.notesatwill.client.AiGreetingClient;
import com.sakuya.notesatwill.client.AiParseClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.time.Duration;

/**
 * AI 客户端装配。
 *
 * <p><b>★ 核心约束：一个客户端只能有一个读超时</b>。
 * 超时属于底层的 {@code ClientHttpRequestFactory}，被整个客户端共享，
 * 声明式接口<b>无法按方法设超时</b>。
 * 所以「一个接口组 = 一个接口 + 一个 Bean + 一次 {@link #createClient} 调用」——
 * 增加新接口只需再写一个 3 行的 Bean，不要试图往现有客户端里塞方法。
 *
 * <p><b>★ 连接超时统一 3 秒，读超时才按接口区分</b>：
 * <ul>
 *   <li><b>连接超时</b>是"连不上"（Python 没启动），目的是<b>快速失败</b> ——
 *       用户等 3 秒就知道 AI 不可用，比等 15 秒好得多</li>
 *   <li><b>读超时</b>是"连上了但算得慢"（模型推理），必须按接口的业务等待预期给足</li>
 * </ul>
 * 两者是不同性质的等待，不该用同一个值。
 *
 * <p><b>★ 为什么 {@link #createClient} 是包级可见的 static 方法</b>：
 * 这样测试可以传入自定义的 {@code AiProperties}（指向一个故意不响应的 socket），
 * 用<b>真实行为</b>验证"不同读超时真的生效"。
 * {@code SimpleClientHttpRequestFactory} 只有 setter 没有 getter，
 * 无法通过读属性来断言，只能测行为。
 *
 * @author sakuya
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiClientConfig {

    /**
     * 连接超时：Python 没启动时要快速失败，不要让用户干等。
     *
     * <p>所有 AI 接口共用这个值 —— "连不上"的等待没有业务差异。
     */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    // ========================================================================
    // 各接口一个 Bean（新增接口时照着复制，改 3 处：接口类型 / 超时字段 / 名字）
    // ========================================================================

    /**
     * 账单解析客户端，读超时取 {@code notes-at-will.ai.timeouts.parse-bill}（默认 15s）。
     */
    @Bean
    public AiParseClient aiParseClient(AiProperties properties) {
        return createClient(properties,
                properties.timeouts().parseBill(),
                AiParseClient.class,
                "parse-bill");
    }

    /**
     * 消费点评客户端，读超时取 {@code notes-at-will.ai.timeouts.analyze}（默认 15s）。
     *
     * <p>与解析客户端分开：两者当前都是 15s，但<b>语义不同</b>
     * （解析失败要报错、点评失败要静默降级），将来调优互不牵连。
     */
    @Bean
    public AiAnalyzeClient aiAnalyzeClient(AiProperties properties) {
        return createClient(properties,
                properties.timeouts().analyze(),
                AiAnalyzeClient.class,
                "analyze");
    }

    /**
     * 每日问候客户端，读超时取 {@code notes-at-will.ai.timeouts.greeting}（默认 <b>3s</b>）。
     *
     * <p><b>★★ 3 秒是全项目最短的 AI 超时，这是刻意的</b>：
     * 问候是<b>首屏接口</b>，用户打开页面就在等。
     * 让用户白屏 15 秒，远不如 3 秒后用模板问候 ——
     * <b>有内容的模板 > 空白等待 > 转圈 15 秒</b>。
     *
     * <p><b>★ 所以它绝不能塞进 AiParseClient</b>：那会让 3 秒的首屏接口
     * 继承 15 秒的读超时，首页在 Python 慢的时候会白屏十几秒。
     */
    @Bean
    public AiGreetingClient aiGreetingClient(AiProperties properties) {
        return createClient(properties,
                properties.timeouts().greeting(),
                AiGreetingClient.class,
                "greeting");
    }

    /*
     * 二期再补（双向自由聊天，当前一期不做）：
     *
     *   @Bean AiChatClient aiChatClient(AiProperties p) {
     *       return createClient(p, p.timeouts().chat(), AiChatClient.class, "chat"); }
     *
     * ★ 同理：超时按接口区分，不要合并客户端。
     */

    // ========================================================================
    // 共用装配逻辑
    // ========================================================================

    /**
     * 按给定读超时装配一个声明式 HTTP 客户端。
     *
     * @param properties     AI 配置（取 baseUrl）
     * @param readTimeoutMs  该接口的读超时（毫秒）
     * @param type           声明式接口类型
     * @param name           接口名，仅用于日志，便于确认"哪个客户端是多少秒"
     * @return 可直接注入的客户端代理
     */
    static <T> T createClient(AiProperties properties, long readTimeoutMs, Class<T> type, String name) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();

        T client = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(type);

        log.info("AI 客户端已装配：[{}] baseUrl={}，连接超时={}ms，读超时={}ms",
                name, properties.baseUrl(), CONNECT_TIMEOUT.toMillis(), readTimeoutMs);
        return client;
    }
}
