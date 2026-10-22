package com.sakuya.notesatwill;

import com.sakuya.notesatwill.entity.DTO.AiBillItem;
import com.sakuya.notesatwill.entity.DTO.ai.PythonParseRequest;
import com.sakuya.notesatwill.entity.DTO.ai.PythonParseResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「Java ↔ Python」线上格式的契约测试。
 *
 * <p><b>★★ 为什么必须单独测这个</b>：
 * 项目全局的 Jackson 配置把 {@code LocalDateTime} 绑定成
 * {@code "yyyy-MM-dd HH:mm:ss"}（空格分隔），而 Python 的 Pydantic 输出的是
 * ISO-8601 {@code "2026-10-17T12:00:00"}（带 T）。
 * <b>两者不兼容，直接对接会反序列化失败。</b>
 *
 * <p>解法是在线上 DTO 的时间字段上加 {@code @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")}
 * 单独覆盖。但这个覆盖是否真能压过"模块里注册的固定 formatter 的序列化器"，
 * 必须实测 —— 如果序列化器在构造时就把 formatter 烘死了，注解是无效的。
 *
 * <p>本测试同时钉死两件事：
 * <ol>
 *   <li><b>线上契约走 ISO</b>（带 T），与 Python 对齐</li>
 *   <li><b>面向浏览器的 DTO 仍走空格格式</b>，不被这个改动影响</li>
 * </ol>
 * 第二条同样重要 —— 否则为了对接 Python 改坏了前端契约。
 *
 * @author sakuya
 */
@SpringBootTest
class AiWireFormatTest {

    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 17, 12, 30, 45);

    // ==================== ① 请求：Java → Python ====================

    @Test
    void requestSerializesNowAsIsoWithT() {
        System.out.println("\n===== 请求体：now 必须是 ISO 格式（带 T）=====");

        PythonParseRequest req = new PythonParseRequest(
                "昨天吃饭25",
                T,
                List.of(new PythonParseRequest.Category(1001L, "餐饮", "EXPENSE")),
                List.of(new PythonParseRequest.Account(5L, "微信")));

        String json = objectMapper.writeValueAsString(req);
        System.out.println("  序列化结果: " + json);

        // ★ 核心断言：必须带 T，不能是空格
        assertTrue(json.contains("\"now\":\"2026-10-17T12:30:45\""),
                "★ now 必须序列化成 ISO-8601（带 T），实际：" + json);
        assertTrue(!json.contains("\"now\":\"2026-10-17 12:30:45\""),
                "★ 不能是空格分隔的格式，否则 Python 侧虽能容忍但契约不统一");

        // 顺带确认嵌套候选也正确
        assertTrue(json.contains("\"id\":1001"), "分类 id 必须存在");
        assertTrue(json.contains("\"type\":\"EXPENSE\""), "分类 type 必须存在");
        System.out.println("  ✓ now 序列化为 2026-10-17T12:30:45（ISO，带 T）");
    }

    // ==================== ② 响应：Python → Java ====================

    @Test
    void responseDeserializesIsoDateTime() {
        System.out.println("\n===== 响应体：ISO 时间必须能被解析 =====");

        // 模拟 Python 真实返回（注意 billTime 带 T，amount 是字符串形式的 Decimal）
        String pyJson = """
                {
                  "success": true,
                  "bills": [
                    {
                      "amount": "25.50",
                      "type": "EXPENSE",
                      "categoryId": 1011,
                      "categoryName": "餐饮/午餐",
                      "accountHint": "微信",
                      "billTime": "2026-10-17T12:00:00",
                      "remark": "午饭",
                      "confidence": 0.92
                    }
                  ],
                  "unmatched": []
                }
                """;

        PythonParseResponse resp = objectMapper.readValue(pyJson, PythonParseResponse.class);
        System.out.println("  解析结果: success=" + resp.success()
                + ", bills=" + resp.bills().size());

        assertTrue(resp.success());
        assertEquals(1, resp.bills().size());
        PythonParseResponse.Bill bill = resp.bills().get(0);

        // ★ 核心断言：ISO 时间被正确解析
        assertNotNull(bill.billTime(), "★ billTime 必须解析成功（不能是 null）");
        assertEquals(T.withHour(12).withMinute(0).withSecond(0), bill.billTime(),
                "★ ISO 时间应被正确解析，实际：" + bill.billTime());
        System.out.println("  ✓ billTime = " + bill.billTime());

        // ★ 字符串形式的金额必须能进 BigDecimal（Python 侧 Decimal 的默认序列化行为）
        assertEquals(0, bill.amount().compareTo(new BigDecimal("25.50")),
                "★ 字符串形式的 amount 必须能被 Jackson 解析成 BigDecimal，实际：" + bill.amount());
        System.out.println("  ✓ amount（字符串 \"25.50\"）→ BigDecimal " + bill.amount());

        assertEquals(1011L, bill.categoryId());
        assertEquals("微信", bill.accountHint());
    }

    @Test
    void responseToleratesUnknownFields() {
        System.out.println("\n===== 响应体：多余字段不能导致失败 =====");
        String pyJson = """
                {"success": true, "bills": [], "unmatched": [], "extraTop": 1, "model": "deepseek-flash"}
                """;
        PythonParseResponse resp = objectMapper.readValue(pyJson, PythonParseResponse.class);
        assertTrue(resp.success());
        System.out.println("  ✓ 多了 extraTop / model 两个字段也能正常解析（ignoreUnknown）");
    }

    // ==================== ③ 反向：不能改坏前端契约 ====================

    @Test
    void browserFacingDtoStillUsesSpaceFormat() {
        System.out.println("\n===== 面向浏览器的 DTO 仍必须是空格格式 =====");

        AiBillItem item = new AiBillItem(
                new BigDecimal("25.50"), "EXPENSE", 1011L, "餐饮/午餐",
                null, "微信", T, "午饭", new BigDecimal("0.92"));

        String json = objectMapper.writeValueAsString(item);
        System.out.println("  序列化结果: " + json);

        // ★ 关键：为了让 Python 对接而加的 @JsonFormat 不能"传染"到公开接口
        assertTrue(json.contains("\"billTime\":\"2026-10-17 12:30:45\""),
                "★ 前端契约必须保持 yyyy-MM-dd HH:mm:ss（空格），实际：" + json);
        assertTrue(!json.contains("2026-10-17T12:30:45"),
                "★ 公开接口不能出现 ISO 的 T 格式");
        System.out.println("  ✓ 前端 DTO 保持 2026-10-17 12:30:45（空格），未被 ISO 影响");
    }
}
