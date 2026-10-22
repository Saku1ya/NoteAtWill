package com.sakuya.notesatwill;

import com.sakuya.notesatwill.client.AiAnalyzeClient;
import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.DTO.BillRequest;
import com.sakuya.notesatwill.entity.DTO.PersonaRequest;
import com.sakuya.notesatwill.entity.DTO.ai.PythonAnalyzeRequest;
import com.sakuya.notesatwill.entity.DTO.ai.PythonAnalyzeResponse;
import com.sakuya.notesatwill.entity.VO.AiCommentVO;
import com.sakuya.notesatwill.entity.VO.CategoryRatioVO;
import com.sakuya.notesatwill.entity.VO.StatsSummaryVO;
import com.sakuya.notesatwill.service.BillService;
import com.sakuya.notesatwill.service.BookService;
import com.sakuya.notesatwill.service.PersonaService;
import com.sakuya.notesatwill.service.StatsCacheService;
import com.sakuya.notesatwill.service.StatsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 消费点评（任务 6.5）回归测试。
 *
 * <p><b>★★ 三个核心验收点</b>：
 * <ol>
 *   <li><b>数字口径一致</b>：传给模型的数字必须与 {@code /summary}、
 *       {@code /category-ratio} 完全相同（复用聚合，不重算）</li>
 *   <li><b>失败必须降级为 null</b>：AI 超时/不可用/返回不合法时
 *       <b>不抛异常</b>，只是没有点评 —— 统计页不能因此报错</li>
 *   <li>带上当前生效角色的口吻；没角色也能点评</li>
 * </ol>
 *
 * @author sakuya
 */
@SpringBootTest
class AiCommentTest {

    private static final Long MY_ID = 987501001L;
    /** 用于「越权」用例的另一个用户；★ 它的数据也必须被清理（否则每跑一次漏一条账本） */
    private static final Long OTHER_ID = 987501002L;
    private static final Long CAT_LUNCH = 100102L;
    private static final Long CAT_TAXI = 100202L;
    private static final Long CAT_SALARY = 2001L;

    @Autowired
    private StatsService statsService;

    @Autowired
    private BillService billService;

    @Autowired
    private BookService bookService;

    @Autowired
    private PersonaService personaService;

    @Autowired
    private StatsCacheService cacheService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** ★ 打桩 AI 点评客户端：本测试不需要 Python 服务 */
    @MockitoBean
    private AiAnalyzeClient aiAnalyzeClient;

    private Long bookId;

    @BeforeEach
    void setUp() {
        clean();
        bookId = bookService.createDefaultBook(MY_ID).getId();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        // ★ 必须连 OTHER_ID 一起清：越权用例会给它建账本，
        //   只清 MY_ID 会让每次运行都往库里留一条孤儿账本。
        for (Long u : new Long[]{MY_ID, OTHER_ID}) {
            jdbcTemplate.update("DELETE FROM t_ai_persona WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_budget WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_account_flow WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_chat_message WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_bill WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_account WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_book WHERE user_id = ?", u);
            cacheService.evictUser(u);
        }
    }

    private void spend(LocalDate day, String amount, Long catId) {
        billService.createBill(MY_ID, new BillRequest(
                bookId, catId, BillService.TYPE_EXPENSE,
                new BigDecimal(amount), safeTime(day), "测试", null));
    }

    private void earn(LocalDate day, String amount) {
        billService.createBill(MY_ID, new BillRequest(
                bookId, CAT_SALARY, BillService.TYPE_INCOME,
                new BigDecimal(amount), safeTime(day), "收入", null));
    }

    /** 今天用"此刻减 1 分钟"，避免落在未来被 BILL_TIME_FUTURE 拒绝。 */
    private java.time.LocalDateTime safeTime(LocalDate day) {
        if (day.equals(LocalDate.now())) {
            return java.time.LocalDateTime.now().minusMinutes(1);
        }
        return day.atTime(12, 0);
    }

    private PythonAnalyzeResponse ok(String comment) {
        return new PythonAnalyzeResponse(true, comment, null);
    }

    // ==================== 正常路径 ====================

    @Test
    void returnsCommentOnSuccess() {
        System.out.println("\n===== 正常生成点评 =====");
        spend(LocalDate.now().minusDays(1), "1250.00", CAT_LUNCH);
        when(aiAnalyzeClient.analyze(any())).thenReturn(ok("这个月花得挺克制。"));

        AiCommentVO vo = statsService.aiComment(MY_ID, bookId, null, null);

        assertNotNull(vo, "成功时应返回点评");
        assertEquals("这个月花得挺克制。", vo.comment());
        assertNotNull(vo.month());
        System.out.println("  ✓ month=" + vo.month() + "，comment=" + vo.comment());
    }

    @Test
    void monthIsFormatted() {
        System.out.println("\n===== month 形如 yyyy-MM =====");
        when(aiAnalyzeClient.analyze(any())).thenReturn(ok("好的"));

        AiCommentVO vo = statsService.aiComment(MY_ID, bookId, null, null);
        assertTrue(vo.month().matches("\\d{4}-\\d{2}"), "实际：" + vo.month());
        System.out.println("  ✓ " + vo.month());
    }

    // ==================== ★★ 数字一致性 ====================

    @Test
    void numbersMatchSummaryExactly() {
        System.out.println("\n===== ★★ 传给模型的数字 = 汇总接口的数字 =====");
        LocalDate d = LocalDate.now().minusDays(2);
        earn(d, "8000.00");
        spend(d, "850.00", CAT_LUNCH);
        spend(d, "400.00", CAT_TAXI);

        when(aiAnalyzeClient.analyze(any())).thenReturn(ok("点评"));

        LocalDate start = LocalDate.now().withDayOfMonth(1);
        LocalDate end = LocalDate.now();

        StatsSummaryVO summary = statsService.summary(MY_ID, bookId, start, end);
        statsService.aiComment(MY_ID, bookId, start, end);

        ArgumentCaptor<PythonAnalyzeRequest> captor =
                ArgumentCaptor.forClass(PythonAnalyzeRequest.class);
        verify(aiAnalyzeClient).analyze(captor.capture());
        PythonAnalyzeRequest sent = captor.getValue();

        System.out.println("  汇总: 收入=" + summary.income() + " 支出=" + summary.expense()
                + " 结余=" + summary.surplus() + " 日均=" + summary.dailyAverage()
                + " days=" + summary.days());
        System.out.println("  发给模型: 收入=" + sent.income() + " 支出=" + sent.expense()
                + " 结余=" + sent.surplus() + " 日均=" + sent.dailyAverage()
                + " days=" + sent.days());

        assertEquals(0, summary.income().compareTo(sent.income()), "★ 收入必须一致");
        assertEquals(0, summary.expense().compareTo(sent.expense()), "★ 支出必须一致");
        assertEquals(0, summary.surplus().compareTo(sent.surplus()), "★ 结余必须一致");
        assertEquals(0, summary.dailyAverage().compareTo(sent.dailyAverage()), "★ 日均必须一致");
        assertEquals(summary.days(), sent.days(), "★ 天数必须一致");
        assertEquals(0, summary.maxExpense().compareTo(sent.maxExpense()), "★ 最大单笔必须一致");
        System.out.println("  ✓ 全部一致（因为直接复用了同一个聚合）");
    }

    @Test
    void categoriesMatchRatioEndpoint() {
        System.out.println("\n===== ★ 分类占比 = /category-ratio 的结果 =====");
        LocalDate d = LocalDate.now().minusDays(1);
        spend(d, "850.00", CAT_LUNCH);
        spend(d, "400.00", CAT_TAXI);

        when(aiAnalyzeClient.analyze(any())).thenReturn(ok("点评"));

        LocalDate start = LocalDate.now().withDayOfMonth(1);
        LocalDate end = LocalDate.now();

        List<CategoryRatioVO> ratio = statsService.categoryRatio(
                MY_ID, bookId, "EXPENSE", start, end, false);
        statsService.aiComment(MY_ID, bookId, start, end);

        ArgumentCaptor<PythonAnalyzeRequest> captor =
                ArgumentCaptor.forClass(PythonAnalyzeRequest.class);
        verify(aiAnalyzeClient).analyze(captor.capture());
        List<PythonAnalyzeRequest.CategoryStat> sent = captor.getValue().topCategories();

        System.out.println("  占比接口: " + ratio.stream()
                .map(r -> r.categoryName() + "=" + r.amount()).toList());
        System.out.println("  发给模型: " + sent.stream()
                .map(c -> c.name() + "=" + c.amount()).toList());

        assertEquals(ratio.size(), sent.size(), "分类条数一致（本例 2 条，未超 Top5）");
        for (int i = 0; i < ratio.size(); i++) {
            assertEquals(ratio.get(i).categoryName(), sent.get(i).name(), "顺序与名字一致");
            assertEquals(0, ratio.get(i).amount().compareTo(sent.get(i).amount()), "金额一致");
            assertEquals(0, ratio.get(i).percent().compareTo(sent.get(i).percent()), "占比一致");
        }
        System.out.println("  ✓ 分类、金额、占比全部一致");
    }

    @Test
    void topCategoriesLimitedTo5() {
        System.out.println("\n===== Top N 上限 5 条 =====");
        LocalDate d = LocalDate.now().minusDays(1);
        // ★ 必须用【一级分类】：categoryRatio 会把二级上卷到一级，
        //   若用 100101/100102/100103 这类二级，最终只会得到 3 个一级分组，
        //   根本测不到"截断到 5"这条逻辑。（这里踩过一次）
        Long[] l1Cats = {1001L, 1002L, 1003L, 1004L, 1005L, 1006L, 1007L};
        for (int i = 0; i < l1Cats.length; i++) {
            spend(d, String.valueOf(100 * (i + 1)), l1Cats[i]);
        }

        when(aiAnalyzeClient.analyze(any())).thenReturn(ok("点评"));
        statsService.aiComment(MY_ID, bookId, null, null);

        ArgumentCaptor<PythonAnalyzeRequest> captor =
                ArgumentCaptor.forClass(PythonAnalyzeRequest.class);
        verify(aiAnalyzeClient).analyze(captor.capture());

        System.out.println("  一级分类共 7 个，实际送入=" + captor.getValue().topCategories().size());
        assertEquals(5, captor.getValue().topCategories().size(),
                "★ 最多送 5 个，避免 Prompt 过长");
        System.out.println("  ✓ 截断到 5 条（按金额降序取前 5）");
    }

    @Test
    void topCategoriesAreSortedByAmountDesc() {
        System.out.println("\n===== Top 分类按金额降序 =====");
        LocalDate d = LocalDate.now().minusDays(1);
        spend(d, "100.00", 1001L);   // 餐饮 小
        spend(d, "900.00", 1002L);   // 交通 大
        spend(d, "500.00", 1003L);   // 购物 中

        when(aiAnalyzeClient.analyze(any())).thenReturn(ok("点评"));
        statsService.aiComment(MY_ID, bookId, null, null);

        ArgumentCaptor<PythonAnalyzeRequest> captor =
                ArgumentCaptor.forClass(PythonAnalyzeRequest.class);
        verify(aiAnalyzeClient).analyze(captor.capture());
        var top = captor.getValue().topCategories();

        System.out.println("  顺序: " + top.stream().map(c -> c.name() + "=" + c.amount()).toList());
        assertEquals("交通", top.get(0).name(), "★ 金额最大的排第一");
        assertEquals(0, top.get(0).amount().compareTo(new BigDecimal("900.00")));
        assertEquals("购物", top.get(1).name());
        assertEquals("餐饮", top.get(2).name());
        System.out.println("  ✓ 降序正确");
    }

    // ==================== ★★ 降级 ====================

    @Test
    void returnsNullWhenAiReturnsFailure() {
        System.out.println("\n===== ★★ AI 返回 success=false → 降级为 null（不报错）=====");
        spend(LocalDate.now().minusDays(1), "100.00", CAT_LUNCH);
        when(aiAnalyzeClient.analyze(any())).thenReturn(
                new PythonAnalyzeResponse(false, null, "没有识别出内容"));

        AiCommentVO vo = statsService.aiComment(MY_ID, bookId, null, null);

        assertNull(vo, "★ 失败时应返回 null，而不是抛异常");
        System.out.println("  ✓ 返回 null，统计页不受影响");
    }

    @Test
    void returnsNullWhenCommentBlank() {
        System.out.println("\n===== 空点评也算失败 =====");
        when(aiAnalyzeClient.analyze(any())).thenReturn(ok("   "));

        assertNull(statsService.aiComment(MY_ID, bookId, null, null));
        System.out.println("  ✓ 返回 null");
    }

    @Test
    void returnsNullOnTimeout() {
        System.out.println("\n===== ★★ 超时 → 降级为 null（统计页不能崩）=====");
        spend(LocalDate.now().minusDays(1), "100.00", CAT_LUNCH);
        when(aiAnalyzeClient.analyze(any())).thenThrow(
                new ResourceAccessException("timeout", new SocketTimeoutException("Read timed out")));

        AiCommentVO vo = statsService.aiComment(MY_ID, bookId, null, null);

        assertNull(vo, "★ 超时不应把异常抛给 Controller");
        System.out.println("  ✓ 返回 null（HTTP 仍是 200）");
    }

    @Test
    void returnsNullOnConnectionFailure() {
        System.out.println("\n===== ★★ Python 没启动 → 降级为 null =====");
        when(aiAnalyzeClient.analyze(any())).thenThrow(
                new ResourceAccessException("I/O error", new java.net.ConnectException("refused")));

        assertNull(statsService.aiComment(MY_ID, bookId, null, null));
        System.out.println("  ✓ 返回 null");
    }

    @Test
    void returnsNullOnNullResponse() {
        System.out.println("\n===== 空响应 → 降级 =====");
        when(aiAnalyzeClient.analyze(any())).thenReturn(null);
        assertNull(statsService.aiComment(MY_ID, bookId, null, null));
        System.out.println("  ✓ 返回 null");
    }

    @Test
    void summaryStillWorksWhenAiFails() {
        System.out.println("\n===== ★★ 点评失败不影响其它统计接口 =====");
        spend(LocalDate.now().minusDays(1), "500.00", CAT_LUNCH);
        when(aiAnalyzeClient.analyze(any())).thenThrow(new ResourceAccessException("down"));

        assertNull(statsService.aiComment(MY_ID, bookId, null, null));

        // 其它接口照常工作
        StatsSummaryVO summary = statsService.summary(MY_ID, bookId, null, null);
        assertEquals(0, summary.expense().compareTo(new BigDecimal("500.00")));
        assertFalse(statsService.categoryRatio(MY_ID, bookId, "EXPENSE", null, null, false).isEmpty());
        System.out.println("  ✓ 点评 null，但 summary/ratio 正常返回 500.00");
    }

    // ==================== 角色口吻 ====================

    @Test
    void includesActivePersona() {
        System.out.println("\n===== ★ 带上当前生效角色的口吻 =====");
        personaService.create(MY_ID, new PersonaRequest(
                3001L, null, null, null, null, null, null, null));

        when(aiAnalyzeClient.analyze(any())).thenReturn(ok("喵~ 这个月不错"));
        statsService.aiComment(MY_ID, bookId, null, null);

        ArgumentCaptor<PythonAnalyzeRequest> captor =
                ArgumentCaptor.forClass(PythonAnalyzeRequest.class);
        verify(aiAnalyzeClient).analyze(captor.capture());

        System.out.println("  personaName=" + captor.getValue().personaName());
        assertEquals("小小鹦鹉", captor.getValue().personaName());
        assertNotNull(captor.getValue().personaPersonality());
        System.out.println("  ✓ 角色名与人设都传给了模型");
    }

    @Test
    void worksWithoutPersona() {
        System.out.println("\n===== 没有角色也能点评（用中性语气）=====");
        when(aiAnalyzeClient.analyze(any())).thenReturn(ok("点评"));

        AiCommentVO vo = statsService.aiComment(MY_ID, bookId, null, null);

        ArgumentCaptor<PythonAnalyzeRequest> captor =
                ArgumentCaptor.forClass(PythonAnalyzeRequest.class);
        verify(aiAnalyzeClient).analyze(captor.capture());
        assertNull(captor.getValue().personaName(), "没有角色时应为 null");
        assertNotNull(vo, "★ 没角色不能导致点评失败");
        System.out.println("  ✓ personaName=null，但点评正常返回");
    }

    @Test
    void usesInactivePersonaNotActive() {
        System.out.println("\n===== 只用【生效】的角色，不用未激活的 =====");
        var a = personaService.create(MY_ID, new PersonaRequest(
                3001L, null, null, null, null, null, null, null));   // 自动激活
        personaService.create(MY_ID, new PersonaRequest(
                3002L, null, null, null, null, null, null, null));   // 未激活

        when(aiAnalyzeClient.analyze(any())).thenReturn(ok("点评"));
        statsService.aiComment(MY_ID, bookId, null, null);

        ArgumentCaptor<PythonAnalyzeRequest> captor =
                ArgumentCaptor.forClass(PythonAnalyzeRequest.class);
        verify(aiAnalyzeClient).analyze(captor.capture());
        System.out.println("  生效角色=" + captor.getValue().personaName());
        assertEquals("小小鹦鹉", captor.getValue().personaName(),
                "★ 应该用生效的那个（小小鹦鹉），不是后建的稳重管家");
        System.out.println("  ✓ 用的是生效角色");
    }

    // ==================== 越权与边界 ====================

    @Test
    void cannotReadOthersBook() {
        System.out.println("\n===== 越权：不能对别人的账本生成点评 =====");
        Long othersBook = bookService.createDefaultBook(OTHER_ID).getId();

        BusinessException e = assertThrows(BusinessException.class,
                () -> statsService.aiComment(MY_ID, othersBook, null, null));
        assertEquals(ErrorCode.BOOK_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")");
    }

    @Test
    void emptyBookStillGeneratesComment() {
        System.out.println("\n===== ★ 没有任何账单也能点评（模型会讲「本月还没记账」）=====");
        when(aiAnalyzeClient.analyze(any())).thenReturn(ok("本月还没有支出记录。"));

        AiCommentVO vo = statsService.aiComment(MY_ID, bookId, null, null);

        assertNotNull(vo);
        ArgumentCaptor<PythonAnalyzeRequest> captor =
                ArgumentCaptor.forClass(PythonAnalyzeRequest.class);
        verify(aiAnalyzeClient).analyze(captor.capture());
        assertEquals(0, captor.getValue().expense().compareTo(BigDecimal.ZERO));
        assertTrue(captor.getValue().topCategories().isEmpty());
        assertNull(captor.getValue().maxExpense());
        System.out.println("  ✓ 支出 0、分类空、maxExpense=null，仍正常调用");
    }
}
