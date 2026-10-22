package com.sakuya.notesatwill;

import com.sakuya.notesatwill.client.AiParseClient;
import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Account;
import com.sakuya.notesatwill.entity.DTO.AiParseRequest;
import com.sakuya.notesatwill.entity.DTO.ai.PythonParseRequest;
import com.sakuya.notesatwill.entity.DTO.ai.PythonParseResponse;
import com.sakuya.notesatwill.entity.VO.AiParseVO;
import com.sakuya.notesatwill.mapper.AccountMapper;
import com.sakuya.notesatwill.service.AiService;
import com.sakuya.notesatwill.service.BookService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 解析（{@code AiService.parse}）的测试。
 *
 * <p>用 {@code @MockitoBean} 替换掉 {@link AiParseClient}，
 * 这样<b>不需要 Python 服务在运行</b>，也能覆盖全部路径：
 * <ul>
 *   <li>正常解析：草稿返回、日志 PENDING、分类校验、时间兜底</li>
 *   <li><b>降级（任务 4.10）</b>：Python 停掉 / 超时 → 返回业务错误码而不是 500</li>
 *   <li>防幻觉：编造的 categoryId / 类型不匹配 → 置 null</li>
 *   <li>候选组装：分类树压平、二级命名「父/子」、收支两棵树都取</li>
 * </ul>
 *
 * @author sakuya
 */
@SpringBootTest
class AiParseTest {

    private static final Long MY_ID = 987491001L;
    private static final Long CAT_DINING = 1001L;    // 餐饮（EXPENSE）
    private static final Long CAT_SALARY = 2001L;    // 职业收入（INCOME）

    @Autowired
    private AiService aiService;

    @Autowired
    private BookService bookService;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** ★ 替换真实客户端：测试不需要 Python 服务在运行 */
    @MockitoBean
    private AiParseClient aiClient;

    @BeforeEach
    void setUp() {
        clean();
        bookService.createDefaultBook(MY_ID);

        Account acc = new Account();
        acc.setUserId(MY_ID);
        acc.setName("微信");
        acc.setType("CASH");
        acc.setInitBalance(BigDecimal.ZERO);
        acc.setBalance(BigDecimal.ZERO);
        accountMapper.insert(acc);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM t_account_flow WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_bill WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_ai_parse_log WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_account WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_book WHERE user_id = ?", MY_ID);
    }

    private AiParseRequest req(String text) {
        return new AiParseRequest(text);
    }

    private PythonParseResponse.Bill bill(String amount, String type, Long catId,
                                          LocalDateTime time, String remark) {
        return new PythonParseResponse.Bill(new BigDecimal(amount), type, catId,
                null, null, time, remark, new BigDecimal("0.92"));
    }

    private PythonParseResponse ok(List<PythonParseResponse.Bill> bills) {
        return new PythonParseResponse(true, bills, List.of(), null);
    }

    private int logCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_ai_parse_log WHERE user_id = ?", Integer.class, MY_ID);
        return n == null ? 0 : n;
    }

    private String logStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM t_ai_parse_log WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, MY_ID);
    }

    // ==================== 正常解析 ====================

    @Test
    void parsesMultipleBillsAndSavesPendingLog() {
        System.out.println("\n===== ★ 正常解析：返回草稿 + 日志 PENDING =====");
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1).withNano(0);

        when(aiClient.parseBill(any())).thenReturn(ok(List.of(
                bill("25.00", "EXPENSE", CAT_DINING, yesterday, "午饭"),
                bill("18.00", "EXPENSE", CAT_DINING, yesterday, "打车"))));

        AiParseVO vo = aiService.parse(MY_ID, req("昨天中午吃饭25，打车18"));

        System.out.printf("  logId=%d，草稿 %d 笔%n", vo.logId(), vo.bills().size());
        assertEquals(2, vo.bills().size());
        assertNotNull(vo.logId(), "必须返回 logId（确认时要用）");

        // ★ 日志必须是 PENDING，且已存原始结果
        assertEquals(1, logCount());
        assertEquals("PENDING", logStatus());
        System.out.println("  ✓ t_ai_parse_log 有一条 PENDING 记录");

        // 分类名由服务端回填
        assertEquals(CAT_DINING, vo.bills().get(0).categoryId());
        System.out.println("  ✓ 分类名回填：" + vo.bills().get(0).categoryName());

        // ★ 草稿不能直接落库
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_bill WHERE user_id = ?", Integer.class, MY_ID),
                "★ 解析阶段绝不能写 t_bill");
        System.out.println("  ✓ t_bill 仍为 0 条（解析不落库）");
    }

    @Test
    void assemblesCandidatesCorrectly() {
        System.out.println("\n===== ★ 候选组装：分类树压平 + 二级「父/子」命名 =====");
        when(aiClient.parseBill(any())).thenReturn(ok(List.of(
                bill("25.00", "EXPENSE", CAT_DINING, LocalDateTime.now(), "x"))));

        aiService.parse(MY_ID, req("吃饭25"));

        ArgumentCaptor<PythonParseRequest> captor = ArgumentCaptor.forClass(PythonParseRequest.class);
        verify(aiClient).parseBill(captor.capture());
        PythonParseRequest sent = captor.getValue();

        System.out.printf("  发送分类 %d 个，账户 %d 个%n",
                sent.categories().size(), sent.accounts().size());

        // ★ 必须同时包含 EXPENSE 与 INCOME 两个类型
        assertTrue(sent.categories().stream().anyMatch(c -> "EXPENSE".equals(c.type())),
                "必须包含支出分类");
        assertTrue(sent.categories().stream().anyMatch(c -> "INCOME".equals(c.type())),
                "必须包含收入分类 —— 否则「发工资8000」无法分类");

        // ★ 二级分类必须命名成「父/子」
        assertTrue(sent.categories().stream().anyMatch(c -> c.name().contains("/")),
                "二级分类名应形如「餐饮/午餐」，实际：" +
                        sent.categories().stream().map(PythonParseRequest.Category::name).toList());

        // 账户候选存在
        assertTrue(sent.accounts().stream().anyMatch(a -> "微信".equals(a.name())));
        System.out.println("  ✓ 收支两棵树都在；二级形如「餐饮/午餐」；账户「微信」在列");

        // now 必须是服务端时间（不可能来自前端）
        assertNotNull(sent.now());
        System.out.println("  ✓ now 由服务端注入：" + sent.now());
    }

    // ==================== 防幻觉 ====================

    @Test
    void hallucinatedCategoryBecomesNull() {
        System.out.println("\n===== ★ 编造的 categoryId → 置 null =====");
        when(aiClient.parseBill(any())).thenReturn(ok(List.of(
                bill("25.00", "EXPENSE", 999999L, LocalDateTime.now(), "编的"))));

        AiParseVO vo = aiService.parse(MY_ID, req("吃饭25"));

        assertNull(vo.bills().get(0).categoryId(), "★ 候选集外的 id 必须置 null");
        assertNull(vo.bills().get(0).categoryName());
        System.out.println("  ✓ categoryId=null，前端可提示用户手选");
    }

    @Test
    void typeMismatchedCategoryBecomesNull() {
        System.out.println("\n===== ★ 分类类型与账单类型相反 → 置 null =====");
        // EXPENSE 账单用了 INCOME 分类
        when(aiClient.parseBill(any())).thenReturn(ok(List.of(
                bill("25.00", "EXPENSE", CAT_SALARY, LocalDateTime.now(), "错配"))));

        AiParseVO vo = aiService.parse(MY_ID, req("吃饭25"));
        assertNull(vo.bills().get(0).categoryId(), "★ EXPENSE 不能用 INCOME 分类");
        System.out.println("  ✓ categoryId=null");
    }

    @Test
    void nullBillTimeFallsBackToNow() {
        System.out.println("\n===== ★ billTime 为 null → 兜底为当前时刻 =====");
        when(aiClient.parseBill(any())).thenReturn(ok(List.of(
                bill("25.00", "EXPENSE", CAT_DINING, null, "没时间"))));

        AiParseVO vo = aiService.parse(MY_ID, req("吃饭25"));
        assertNotNull(vo.bills().get(0).billTime(), "★ 必须兜底，不能留 null");
        System.out.println("  ✓ billTime 兜底为 " + vo.bills().get(0).billTime());
    }

    @Test
    void categoryNameComesFromServerNotPython() {
        System.out.println("\n===== ★ 分类名由服务端回填，不用 Python 给的名字 =====");
        // Python 给了一个对不上的名字
        when(aiClient.parseBill(any())).thenReturn(ok(List.of(
                new PythonParseResponse.Bill(new BigDecimal("25.00"), "EXPENSE", CAT_DINING,
                        "Python瞎编的名字", null, LocalDateTime.now(), "午饭", new BigDecimal("0.9")))));

        AiParseVO vo = aiService.parse(MY_ID, req("吃饭25"));
        assertNotNull(vo.bills().get(0).categoryName());
        assertTrue(!"Python瞎编的名字".equals(vo.bills().get(0).categoryName()),
                "★ 必须用候选集里的名字覆盖");
        System.out.println("  ✓ 回填为：" + vo.bills().get(0).categoryName());
    }

    // ==================== ★★ 降级（任务 4.10）====================

    @Test
    void pythonUnavailableReturnsBusinessErrorNot500() {
        System.out.println("\n===== ★★ 降级：Python 服务没启动 =====");
        // 模拟连接被拒（Python 没启动时的真实异常）
        when(aiClient.parseBill(any())).thenThrow(
                new ResourceAccessException("I/O error", new ConnectException("Connection refused")));

        BusinessException e = assertThrows(BusinessException.class,
                () -> aiService.parse(MY_ID, req("吃饭25")));

        System.out.printf("  抛业务异常 code=%d, msg=%s%n", e.getCode(), e.getMessage());
        assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE.getCode(), e.getCode());
        assertEquals(0, logCount(), "失败时不该留下日志");
        System.out.println("  ✓ 返回 5000（业务错误码），HTTP 仍是 200 —— 不是 500");
    }

    @Test
    void readTimeoutReturnsTimeoutError() {
        System.out.println("\n===== ★★ 降级：读超时（用【实测得到的真实异常形状】）=====");
        // ★★ 这里刻意不用 ResourceAccessException。
        //    实测（AiClientTimeoutTest）读超时的真实形状是"提取响应失败"，
        //    SocketTimeoutException 埋在 cause 链里，外层是普通 RestClientException。
        //    用 ResourceAccessException 来测会给出假信心 —— 之前就写错过一次。
        when(aiClient.parseBill(any())).thenThrow(new RestClientException(
                "Error while extracting response for type "
                        + "[com.sakuya.notesatwill.entity.DTO.ai.PythonParseResponse] "
                        + "and content type [application/octet-stream]",
                new SocketTimeoutException("Read timed out")));

        BusinessException e = assertThrows(BusinessException.class,
                () -> aiService.parse(MY_ID, req("吃饭25")));

        assertEquals(ErrorCode.AI_SERVICE_TIMEOUT.getCode(), e.getCode(),
                "★ 必须靠 cause 链识别出超时，而不是靠异常类");
        assertEquals(0, logCount());
        System.out.printf("  ✓ 返回 %d（超时），与「不可用」区分开%n", e.getCode());
    }

    @Test
    void connectTimeoutAlsoReturnsTimeoutError() {
        System.out.println("\n===== 连接超时（SocketTimeoutException: connect timed out）也算超时 =====");
        when(aiClient.parseBill(any())).thenThrow(new RestClientException(
                "I/O error", new SocketTimeoutException("connect timed out")));

        BusinessException e = assertThrows(BusinessException.class,
                () -> aiService.parse(MY_ID, req("吃饭25")));

        assertEquals(ErrorCode.AI_SERVICE_TIMEOUT.getCode(), e.getCode());
        System.out.println("  ✓ 返回 " + e.getCode());
    }

    @Test
    void resourceAccessExceptionWithTimeoutCauseStillMapsToTimeout() {
        System.out.println("\n===== 兜底：若 Spring 某天改回抛 ResourceAccessException，仍应判为超时 =====");
        when(aiClient.parseBill(any())).thenThrow(
                new ResourceAccessException("timeout", new SocketTimeoutException("Read timed out")));

        BusinessException e = assertThrows(BusinessException.class,
                () -> aiService.parse(MY_ID, req("吃饭25")));

        assertEquals(ErrorCode.AI_SERVICE_TIMEOUT.getCode(), e.getCode());
        System.out.println("  ✓ 父类/子类都能覆盖（因为只按 cause 链判断）");
    }

    @Test
    void otherHttpErrorReturnsUnavailable() {
        System.out.println("\n===== 降级：其它 HTTP 层错误 =====");
        when(aiClient.parseBill(any())).thenThrow(new RestClientException("500 Server Error"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> aiService.parse(MY_ID, req("吃饭25")));
        assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE.getCode(), e.getCode());
        System.out.println("  ✓ 返回 " + e.getCode());
    }

    @Test
    void pythonReportedFailureIsPassedThrough() {
        System.out.println("\n===== 降级：Python 明确报失败，原因要透传 =====");
        when(aiClient.parseBill(any())).thenReturn(new PythonParseResponse(
                false, List.of(), List.of("今天天气不错"), "没有识别出任何账单，请补充金额与用途"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> aiService.parse(MY_ID, req("今天天气不错")));

        System.out.printf("  code=%d, msg=%s%n", e.getCode(), e.getMessage());
        assertEquals(ErrorCode.AI_PARSE_FAILED.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("没有识别出任何账单"),
                "★ Python 的原始原因必须透传给前端，实际：" + e.getMessage());
        System.out.println("  ✓ 原因透传，前端能给出有针对性的提示");
    }

    @Test
    void emptyBillsFromPythonIsRejected() {
        System.out.println("\n===== Python 报成功但一笔都没有 =====");
        when(aiClient.parseBill(any())).thenReturn(ok(List.of()));

        BusinessException e = assertThrows(BusinessException.class,
                () -> aiService.parse(MY_ID, req("吃饭25")));
        assertEquals(ErrorCode.AI_PARSE_EMPTY.getCode(), e.getCode());
        System.out.println("  被拒(" + e.getCode() + ") ✓");
    }

    @Test
    void textTooLongIsRejectedWithoutCallingPython() {
        System.out.println("\n===== 超长输入不浪费一次外部调用 =====");
        String tooLong = "啊".repeat(501);

        BusinessException e = assertThrows(BusinessException.class,
                () -> aiService.parse(MY_ID, req(tooLong)));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), e.getCode());

        // verify 没被调用
        org.mockito.Mockito.verify(aiClient, org.mockito.Mockito.never()).parseBill(any());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")，且完全没有调用 AI 服务");
    }

    @Test
    void blankTextIsRejected() {
        System.out.println("\n===== 空白输入 =====");
        BusinessException e = assertThrows(BusinessException.class,
                () -> aiService.parse(MY_ID, req("   ")));
        assertEquals(ErrorCode.PARAM_MISSING.getCode(), e.getCode());
        System.out.println("  被拒(" + e.getCode() + ") ✓");
    }
}
