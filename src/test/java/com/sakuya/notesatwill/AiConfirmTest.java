package com.sakuya.notesatwill;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Account;
import com.sakuya.notesatwill.entity.AiParseLog;
import com.sakuya.notesatwill.entity.Bill;
import com.sakuya.notesatwill.entity.DTO.AiBillItem;
import com.sakuya.notesatwill.entity.DTO.AiConfirmRequest;
import com.sakuya.notesatwill.entity.VO.AccountCheckVO;
import com.sakuya.notesatwill.mapper.AccountMapper;
import com.sakuya.notesatwill.mapper.AiParseLogMapper;
import com.sakuya.notesatwill.service.AccountFlowService;
import com.sakuya.notesatwill.service.AiService;
import com.sakuya.notesatwill.service.BillService;
import com.sakuya.notesatwill.service.BookService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 确认落库（{@code AiService.confirm} / {@code BillService.confirmAiBills}）的回归测试。
 *
 * <p>核心验收点（任务 4.9）：
 * <ol>
 *   <li><b>多笔一次落库成功</b>，且 {@code source=AI}、{@code ai_log_id} 回填</li>
 *   <li><b>非法 categoryId 被拒且全部回滚</b> —— 不能出现"落了一半"</li>
 *   <li>日志状态从 PENDING 改成 CONFIRMED，且记录用户修正</li>
 *   <li><b>不能重复确认</b>（否则重复记账）</li>
 *   <li>{@code billTime} 为 null 时兜底（三层约定的最后一道防线）</li>
 * </ol>
 *
 * @author sakuya
 */
@SpringBootTest
class AiConfirmTest {

    private static final Long MY_ID = 987490001L;
    private static final Long OTHER_ID = 987490002L;
    private static final Long CAT_DINING = 1001L;    // 餐饮（EXPENSE）
    private static final Long CAT_TRANSPORT = 1002L; // 交通（EXPENSE）
    private static final Long CAT_SALARY = 2001L;    // 职业收入（INCOME）

    @Autowired
    private AiService aiService;

    @Autowired
    private BillService billService;

    @Autowired
    private AccountFlowService flowService;

    @Autowired
    private BookService bookService;

    @Autowired
    private AiParseLogMapper aiParseLogMapper;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long bookId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        clean();
        bookId = bookService.createDefaultBook(MY_ID).getId();

        Account acc = new Account();
        acc.setUserId(MY_ID);
        acc.setName("招行卡");
        acc.setType("CASH");
        acc.setInitBalance(new BigDecimal("1000.00"));
        acc.setBalance(new BigDecimal("1000.00"));
        accountMapper.insert(acc);
        accountId = acc.getId();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (Long u : new Long[]{MY_ID, OTHER_ID}) {
            jdbcTemplate.update("DELETE FROM t_account_flow WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_chat_message WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_bill WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_ai_parse_log WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_account WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_book WHERE user_id = ?", u);
        }
    }

    /** 造一条 PENDING 的解析日志（模拟 /api/ai/parse 的产物）。 */
    private Long newPendingLog(Long userId) {
        AiParseLog logRow = new AiParseLog();
        logRow.setUserId(userId);
        logRow.setRawText("昨天中午吃饭25，打车18");
        logRow.setResultJson("{\"bills\":[]}");
        logRow.setParsedCount(2);
        logRow.setStatus(AiService.STATUS_PENDING);
        logRow.setCostMs(1234);
        aiParseLogMapper.insert(logRow);
        return logRow.getId();
    }

    private String logStatus(Long logId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM t_ai_parse_log WHERE id = ?", String.class, logId);
    }

    private String logCorrectedJson(Long logId) {
        return jdbcTemplate.queryForObject(
                "SELECT corrected_json FROM t_ai_parse_log WHERE id = ?", String.class, logId);
    }

    private int billCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_bill WHERE user_id = ?", Integer.class, MY_ID);
        return n == null ? 0 : n;
    }

    private BigDecimal bal(Long id) {
        return jdbcTemplate.queryForObject("SELECT balance FROM t_account WHERE id = ?",
                BigDecimal.class, id);
    }

    private void assertReconciled(String scene) {
        List<AccountCheckVO> rows = flowService.reconcile(MY_ID);
        List<String> bad = rows.stream().filter(r -> !r.matched())
                .map(r -> r.name() + " diff=" + r.diff()).toList();
        assertTrue(bad.isEmpty(), scene + " 对账不平: " + bad);
    }

    private AiBillItem item(String amount, String type, Long categoryId,
                            Long accId, LocalDateTime time, String remark) {
        return new AiBillItem(new BigDecimal(amount), type, categoryId, null,
                accId, null, time, remark, new BigDecimal("0.9"));
    }

    // ==================== ★ 正常批量落库 ====================

    @Test
    void confirmsMultipleBillsInOneTransaction() {
        System.out.println("\n===== ★ 多笔一次落库成功 =====");
        Long logId = newPendingLog(MY_ID);
        LocalDateTime t = LocalDateTime.now().minusHours(1).withNano(0);

        AiConfirmRequest req = new AiConfirmRequest(logId, bookId, List.of(
                item("25.00", "EXPENSE", CAT_DINING, accountId, t, "午饭"),
                item("18.00", "EXPENSE", CAT_TRANSPORT, null, t, "打车"),
                item("8000.00", "INCOME", CAT_SALARY, null, t, "工资")));

        List<Bill> saved = aiService.confirm(MY_ID, req);

        System.out.printf("  落库 %d 笔，账单总数=%d%n", saved.size(), billCount());
        assertEquals(3, saved.size());
        assertEquals(3, billCount());

        // ★ source 必须是 AI，ai_log_id 必须回填
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT source, ai_log_id, amount FROM t_bill WHERE user_id = ? ORDER BY id", MY_ID);
        for (Map<String, Object> r : rows) {
            assertEquals("AI", r.get("source"), "★ source 必须是 AI");
            assertEquals(logId, ((Number) r.get("ai_log_id")).longValue(), "★ ai_log_id 必须回填");
        }
        System.out.println("  ✓ 3 笔的 source 都是 AI，ai_log_id 都是 " + logId);

        // ★ 日志状态必须变成 CONFIRMED
        assertEquals("CONFIRMED", logStatus(logId));
        assertNotNull(logCorrectedJson(logId), "corrected_json 应记录用户提交的内容");
        System.out.println("  ✓ 日志状态 PENDING → CONFIRMED，corrected_json 已写入");

        // 只有第 1 笔选了账户 → 只有它扣了钱
        assertEquals(0, bal(accountId).compareTo(new BigDecimal("975.00")),
                "只有选了账户的那笔扣钱");
        assertReconciled("批量落库后");
        System.out.println("  ✓ 余额只被「选了账户」的那笔影响，对账通过");
    }

    @Test
    void nullBillTimeFallsBackToNow() {
        System.out.println("\n===== ★ billTime 为 null 时兜底（三层约定第三层）=====");
        Long logId = newPendingLog(MY_ID);

        // 故意传 null —— 模拟"模型没解析出时间、前端也没补"
        AiConfirmRequest req = new AiConfirmRequest(logId, bookId, List.of(
                item("25.00", "EXPENSE", CAT_DINING, null, null, "没时间")));

        Bill saved = aiService.confirm(MY_ID, req).get(0);

        String stored = jdbcTemplate.queryForObject(
                "SELECT bill_time FROM t_bill WHERE id = ?", String.class, saved.getId());
        System.out.println("  未传时间 → 库中 bill_time = " + stored);
        assertNotNull(stored, "★ t_bill.bill_time 是 NOT NULL，必须兜底成功而不是报 SQL 错误");
        System.out.println("  ✓ 服务端兜底为当前时间，未触发 NOT NULL 报错");
    }

    // ==================== ★ 整体回滚 ====================

    @Test
    void invalidCategoryRollsBackEverything() {
        System.out.println("\n===== ★★ 非法分类必须【全部回滚】=====");
        Long logId = newPendingLog(MY_ID);

        // 第 1、2 笔合法，第 3 笔分类不存在
        AiConfirmRequest req = new AiConfirmRequest(logId, bookId, List.of(
                item("25.00", "EXPENSE", CAT_DINING, accountId, LocalDateTime.now(), "午饭"),
                item("18.00", "EXPENSE", CAT_TRANSPORT, null, LocalDateTime.now(), "打车"),
                item("50.00", "EXPENSE", 999999L, null, LocalDateTime.now(), "坏分类")));

        BusinessException e = assertThrows(BusinessException.class,
                () -> aiService.confirm(MY_ID, req));
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), e.getCode());

        System.out.printf("  第 3 笔分类非法 → 抛 %d；账单总数=%d，日志状态=%s%n",
                e.getCode(), billCount(), logStatus(logId));

        // ★★ 核心断言：前 2 笔也不能留下
        assertEquals(0, billCount(), "★ 前 2 笔必须一起回滚，不能出现『落了一半』");
        assertEquals("PENDING", logStatus(logId),
                "★ 日志状态必须仍是 PENDING —— 否则用户看到『已确认但只有 2 笔』");
        assertEquals(0, bal(accountId).compareTo(new BigDecimal("1000.00")),
                "★ 余额也不能被动过（第 1 笔的流水必须回滚）");
        assertReconciled("整体回滚后");
        System.out.println("  ✓ 账单 0 条、日志仍 PENDING、余额未变、对账通过");
    }

    @Test
    void typeMismatchedCategoryRollsBack() {
        System.out.println("\n===== 分类类型与账单类型不一致也要回滚 =====");
        Long logId = newPendingLog(MY_ID);

        // EXPENSE 账单配了 INCOME 分类
        AiConfirmRequest req = new AiConfirmRequest(logId, bookId, List.of(
                item("25.00", "EXPENSE", CAT_SALARY, null, LocalDateTime.now(), "错配")));

        BusinessException e = assertThrows(BusinessException.class,
                () -> aiService.confirm(MY_ID, req));
        assertEquals(ErrorCode.CATEGORY_TYPE_MISMATCH.getCode(), e.getCode());
        assertEquals(0, billCount());
        assertEquals("PENDING", logStatus(logId));
        System.out.println("  被拒(" + e.getCode() + ")，账单 0 条，日志仍 PENDING ✓");
    }

    // ==================== 日志状态与越权 ====================

    @Test
    void cannotConfirmSameLogTwice() {
        System.out.println("\n===== ★ 不能重复确认（防重复记账）=====");
        Long logId = newPendingLog(MY_ID);
        AiConfirmRequest req = new AiConfirmRequest(logId, bookId, List.of(
                item("25.00", "EXPENSE", CAT_DINING, null, LocalDateTime.now(), "午饭")));

        aiService.confirm(MY_ID, req);
        assertEquals(1, billCount());

        BusinessException e = assertThrows(BusinessException.class,
                () -> aiService.confirm(MY_ID, req));
        assertEquals(ErrorCode.AI_LOG_ALREADY_CONFIRMED.getCode(), e.getCode());

        System.out.printf("  第二次确认 → 被拒(%d)；账单总数仍为 %d%n",
                e.getCode(), billCount());
        assertEquals(1, billCount(), "★ 不能重复落库");
        System.out.println("  ✓ 重复确认被拦，账单没有翻倍");
    }

    @Test
    void cannotConfirmOthersLog() {
        System.out.println("\n===== 越权：不能确认别人的解析日志 =====");
        Long othersLog = newPendingLog(OTHER_ID);
        Long otherBook = bookService.createDefaultBook(OTHER_ID).getId();

        AiConfirmRequest req = new AiConfirmRequest(othersLog, otherBook, List.of(
                item("25.00", "EXPENSE", CAT_DINING, null, LocalDateTime.now(), "试图篡改")));

        BusinessException e = assertThrows(BusinessException.class,
                () -> aiService.confirm(MY_ID, req));
        assertEquals(ErrorCode.AI_LOG_NOT_FOUND.getCode(), e.getCode());
        assertEquals(0, billCount());
        System.out.println("  用我的 userId 确认别人的日志 → 被拒(" + e.getCode() + ") ✓");
    }

    @Test
    void nonexistentLogIsRejected() {
        System.out.println("\n===== 不存在的日志 id =====");
        AiConfirmRequest req = new AiConfirmRequest(99999999L, bookId, List.of(
                item("25.00", "EXPENSE", CAT_DINING, null, LocalDateTime.now(), "x")));
        BusinessException e = assertThrows(BusinessException.class,
                () -> aiService.confirm(MY_ID, req));
        assertEquals(ErrorCode.AI_LOG_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  被拒(" + e.getCode() + ") ✓");
    }

    @Test
    void cannotConfirmIntoOthersBook() {
        System.out.println("\n===== 越权：不能把账单落进别人的账本 =====");
        Long logId = newPendingLog(MY_ID);
        Long othersBook = bookService.createDefaultBook(OTHER_ID).getId();

        AiConfirmRequest req = new AiConfirmRequest(logId, othersBook, List.of(
                item("25.00", "EXPENSE", CAT_DINING, null, LocalDateTime.now(), "x")));

        BusinessException e = assertThrows(BusinessException.class,
                () -> aiService.confirm(MY_ID, req));
        assertEquals(ErrorCode.BOOK_NOT_FOUND.getCode(), e.getCode());
        assertEquals(0, billCount());
        System.out.println("  被拒(" + e.getCode() + ")，账单 0 条 ✓");
    }

    @Test
    void cannotConfirmIntoOthersAccount() {
        System.out.println("\n===== 越权：不能用别人的账户 =====");
        Account otherAcc = new Account();
        otherAcc.setUserId(OTHER_ID);
        otherAcc.setName("别人的卡");
        otherAcc.setType("CASH");
        otherAcc.setInitBalance(new BigDecimal("5000.00"));
        otherAcc.setBalance(new BigDecimal("5000.00"));
        accountMapper.insert(otherAcc);

        Long logId = newPendingLog(MY_ID);
        AiConfirmRequest req = new AiConfirmRequest(logId, bookId, List.of(
                item("25.00", "EXPENSE", CAT_DINING, otherAcc.getId(), LocalDateTime.now(), "x")));

        BusinessException e = assertThrows(BusinessException.class,
                () -> aiService.confirm(MY_ID, req));
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND.getCode(), e.getCode());

        assertEquals(0, billCount(), "整体回滚");
        assertEquals(0, bal(otherAcc.getId()).compareTo(new BigDecimal("5000.00")),
                "★ 别人的余额不能被改");
        System.out.println("  被拒(" + e.getCode() + ")，别人余额未变，账单 0 条 ✓");
    }

    // ==================== 与手动记账的对比 ====================

    @Test
    void aiAndManualBillsCoexist() {
        System.out.println("\n===== AI 账单与手动账单共存，source 各自正确 =====");

        // 手动记一笔
        billService.createBill(MY_ID, new com.sakuya.notesatwill.entity.DTO.BillRequest(
                bookId, CAT_DINING, "EXPENSE", new BigDecimal("10.00"),
                LocalDateTime.now(), "手动", accountId));

        // AI 确认一笔
        Long logId = newPendingLog(MY_ID);
        aiService.confirm(MY_ID, new AiConfirmRequest(logId, bookId, List.of(
                item("20.00", "EXPENSE", CAT_TRANSPORT, accountId, LocalDateTime.now(), "AI"))));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT source, ai_log_id, remark FROM t_bill WHERE user_id = ? ORDER BY id", MY_ID);
        rows.forEach(r -> System.out.println("  " + r));

        assertEquals("MANUAL", rows.get(0).get("source"));
        assertEquals(null, rows.get(0).get("ai_log_id"), "手动记账的 ai_log_id 必须是 null");
        assertEquals("AI", rows.get(1).get("source"));
        assertEquals(logId, ((Number) rows.get(1).get("ai_log_id")).longValue());
        System.out.println("  ✓ 两条路径的 source 与 ai_log_id 各自正确");

        assertEquals(0, bal(accountId).compareTo(new BigDecimal("970.00")), "1000 - 10 - 20");
        assertReconciled("混合记账后");
        System.out.println("  ✓ 余额 970.00，对账通过");
    }

    @Test
    void emptyBillsListIsRejected() {
        System.out.println("\n===== 空列表应被拒 =====");
        Long logId = newPendingLog(MY_ID);
        BusinessException e = assertThrows(BusinessException.class,
                () -> aiService.confirm(MY_ID, new AiConfirmRequest(logId, bookId, List.of())));
        assertEquals(ErrorCode.PARAM_MISSING.getCode(), e.getCode());
        System.out.println("  被拒(" + e.getCode() + ") ✓");
    }
}
