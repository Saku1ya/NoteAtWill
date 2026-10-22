package com.sakuya.notesatwill;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.DTO.BillRequest;
import com.sakuya.notesatwill.entity.DTO.BookRequest;
import com.sakuya.notesatwill.entity.VO.BillSummaryVO;
import com.sakuya.notesatwill.entity.VO.BillVO;
import com.sakuya.notesatwill.entity.DTO.BillQuery;
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
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code BillService.summary}（列表页汇总）与共用口径 {@code monthSum} 的回归测试。
 *
 * <p>核心验收标准（计划文档给的）：<b>汇总数与逐条明细相加一致</b>。
 * 所以每个用例都用"明细逐条相加"作为期望值，而不是写死一个数字 ——
 * 那样即使聚合逻辑错了，测试也可能"碰巧通过"。
 *
 * <p>覆盖：
 * <ol>
 *   <li>收入/支出/结余三项都正确，且与明细一致</li>
 *   <li>无数据时返回 <b>0 而不是 null</b></li>
 *   <li>跨月数据不计入</li>
 *   <li>其它账本不计入</li>
 *   <li>其它用户不计入</li>
 *   <li>已逻辑删除的账单不计入</li>
 *   <li><b>时间边界：左闭右开</b>（本月 1 日 00:00 计入，次月 1 日 00:00 排除）</li>
 *   <li>结余可为负</li>
 *   <li>账本不属于我 → 3000</li>
 * </ol>
 *
 * @author sakuya
 */
@SpringBootTest
class BillSummaryTest {

    private static final Long MY_ID = 987510001L;
    private static final Long OTHER_ID = 987510002L;
    private static final Long CAT_DINING = 1001L;
    private static final Long CAT_SALARY = 2001L;

    @Autowired
    private BillService billService;

    @Autowired
    private BookService bookService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        for (Long u : new Long[]{MY_ID, OTHER_ID}) {
            jdbcTemplate.update("DELETE FROM t_account_flow WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_chat_message WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_bill WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_account WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_book WHERE user_id = ?", u);
        }
    }

    /** 本月内某一天的安全时间（避免落到未来）。 */
    private LocalDateTime thisMonthAt(int dayOfMonth) {
        LocalDateTime first = YearMonth.now().atDay(1).atStartOfDay();
        return first.plusDays(dayOfMonth - 1L).plusHours(12);
    }

    private void add(String type, String amount, LocalDateTime time, Long book) {
        billService.createBill(MY_ID, new BillRequest(
                book, "EXPENSE".equals(type) ? CAT_DINING : CAT_SALARY,
                type, new BigDecimal(amount), time, null, null));
    }

    /**
     * 绕过 Service 校验直接插入账单，用于构造"未来/边界"时间的数据。
     * （本月 1 日之后的时间多数会被 BILL_TIME_FUTURE 拒绝，而边界测试恰恰需要它们。）
     */
    private void insertRaw(String type, String amount, LocalDateTime time, Long book, Long userId) {
        jdbcTemplate.update(
                "INSERT INTO t_bill (user_id, book_id, category_id, type, amount, bill_time, source, deleted, create_time, update_time)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'MANUAL', 0, NOW(), NOW())",
                userId, book, "EXPENSE".equals(type) ? CAT_DINING : CAT_SALARY, type,
                new BigDecimal(amount), time);
    }

    /** 用明细逐条相加算出期望值 —— 不写死数字，避免"聚合错了但测试碰巧通过"。 */
    private BigDecimal sumFromDetails(Long userId, Long book, String type, YearMonth month) {
        List<BillVO> all = billService.pageBills(userId, new BillQuery(
                book, type, null, null,
                month.atDay(1).atStartOfDay(),
                month.plusMonths(1).atDay(1).atStartOfDay(),
                null, 1, 1000)).getRecords();
        return all.stream().map(BillVO::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void assertMatchesDetails(BillSummaryVO vo, String scene) {
        YearMonth m = YearMonth.parse(vo.month());
        BigDecimal expIncome = sumFromDetails(MY_ID, bookId, "INCOME", m);
        BigDecimal expExpense = sumFromDetails(MY_ID, bookId, "EXPENSE", m);
        System.out.printf("  [%s] 汇总 income=%s expense=%s surplus=%s；明细相加 income=%s expense=%s%n",
                scene, vo.income(), vo.expense(), vo.surplus(), expIncome, expExpense);
        assertEquals(0, vo.income().compareTo(expIncome), scene + "：收入汇总与明细不一致");
        assertEquals(0, vo.expense().compareTo(expExpense), scene + "：支出汇总与明细不一致");
        assertEquals(0, vo.surplus().compareTo(expIncome.subtract(expExpense)),
                scene + "：结余 != 收入 - 支出");
    }

    // ==================== ★ 核心验收：汇总与明细一致 ====================

    @Test
    void summaryMatchesDetailSum() {
        System.out.println("\n===== ★ 汇总数必须与逐条明细相加一致 =====");
        add("EXPENSE", "25.00", thisMonthAt(1), bookId);
        add("EXPENSE", "30.50", thisMonthAt(2), bookId);
        add("INCOME", "1000.00", thisMonthAt(3), bookId);
        add("INCOME", "200.00", thisMonthAt(4), bookId);
        add("EXPENSE", "0.01", thisMonthAt(5), bookId);

        BillSummaryVO vo = billService.summary(MY_ID, bookId);
        System.out.printf("  month=%s, bookName=%s%n", vo.month(), vo.bookName());
        assertMatchesDetails(vo, "5 笔账单");

        // 手工核对一遍（明细相加 = 55.51 支出 / 1200 收入）
        assertEquals(0, vo.expense().compareTo(new BigDecimal("55.51")), "25 + 30.50 + 0.01");
        assertEquals(0, vo.income().compareTo(new BigDecimal("1200.00")), "1000 + 200");
        assertEquals(0, vo.surplus().compareTo(new BigDecimal("1144.49")));
        assertEquals(bookId, vo.bookId());
        assertEquals("默认账本", vo.bookName(), "卡片上必须显示账本名");
        assertEquals(YearMonth.now().toString(), vo.month(), "月份格式应为 yyyy-MM");
        System.out.println("  ✓ 收入/支出/结余三项都与明细一致，且账本名与月份正确");
    }

    @Test
    void emptyMonthReturnsZeroNotNull() {
        System.out.println("\n===== ★ 无数据时返回 0，而不是 null =====");
        BillSummaryVO vo = billService.summary(MY_ID, bookId);

        assertNotNull(vo.income(), "income 不能为 null（SUM 无行时返回 NULL，必须 COALESCE）");
        assertNotNull(vo.expense(), "expense 不能为 null");
        assertNotNull(vo.surplus(), "surplus 不能为 null");
        assertEquals(0, vo.income().compareTo(BigDecimal.ZERO));
        assertEquals(0, vo.expense().compareTo(BigDecimal.ZERO));
        assertEquals(0, vo.surplus().compareTo(BigDecimal.ZERO));
        System.out.println("  income=0, expense=0, surplus=0（三个字段都不是 null）✓");
    }

    @Test
    void surplusCanBeNegative() {
        System.out.println("\n===== 结余可以为负（本月花超了）=====");
        add("INCOME", "100.00", thisMonthAt(1), bookId);
        add("EXPENSE", "350.00", thisMonthAt(2), bookId);

        BillSummaryVO vo = billService.summary(MY_ID, bookId);
        System.out.printf("  收入 %s − 支出 %s = 结余 %s%n", vo.income(), vo.expense(), vo.surplus());
        assertEquals(0, vo.surplus().compareTo(new BigDecimal("-250.00")));
        System.out.println("  ✓ 结余 -250.00（不能因负号被截断或取绝对值）");
    }

    // ==================== 口径隔离 ====================

    @Test
    void otherMonthsAreExcluded() {
        System.out.println("\n===== 跨月数据不计入本月 =====");
        add("EXPENSE", "25.00", thisMonthAt(1), bookId);                       // 本月
        add("EXPENSE", "999.00", YearMonth.now().minusMonths(1).atDay(15).atTime(12, 0), bookId);  // 上月

        BillSummaryVO vo = billService.summary(MY_ID, bookId);
        System.out.printf("  本月支出 = %s（上月那笔 999 不该进来）%n", vo.expense());
        assertEquals(0, vo.expense().compareTo(new BigDecimal("25.00")));
        assertMatchesDetails(vo, "含上月数据");
        System.out.println("  ✓ 只统计本月");
    }

    @Test
    void otherBooksAreExcluded() {
        System.out.println("\n===== 其它账本不计入 =====");
        Long book2 = bookService.create(MY_ID,
                new BookRequest("家庭", "home", "#FF0000", null)).getId();
        add("EXPENSE", "25.00", thisMonthAt(1), bookId);
        add("EXPENSE", "500.00", thisMonthAt(2), book2);

        BillSummaryVO vo1 = billService.summary(MY_ID, bookId);
        BillSummaryVO vo2 = billService.summary(MY_ID, book2);
        System.out.printf("  默认账本支出=%s（bookName=%s）；家庭支出=%s（bookName=%s）%n",
                vo1.expense(), vo1.bookName(), vo2.expense(), vo2.bookName());
        assertEquals(0, vo1.expense().compareTo(new BigDecimal("25.00")));
        assertEquals(0, vo2.expense().compareTo(new BigDecimal("500.00")));
        assertEquals("家庭", vo2.bookName());
        System.out.println("  ✓ 每个账本各自统计");
    }

    @Test
    void otherUsersAreExcluded() {
        System.out.println("\n===== 其它用户不计入 =====");
        Long otherBook = bookService.createDefaultBook(OTHER_ID).getId();
        insertRaw("EXPENSE", "888.00", thisMonthAt(1), otherBook, OTHER_ID);
        add("EXPENSE", "25.00", thisMonthAt(1), bookId);

        BillSummaryVO vo = billService.summary(MY_ID, bookId);
        System.out.printf("  我的账本支出 = %s（别人的 888 不该进来）%n", vo.expense());
        assertEquals(0, vo.expense().compareTo(new BigDecimal("25.00")));
        System.out.println("  ✓ 数据隔离正确");
    }

    @Test
    void deletedBillsAreExcluded() {
        System.out.println("\n===== 已逻辑删除的账单不计入 =====");
        add("EXPENSE", "25.00", thisMonthAt(1), bookId);
        add("EXPENSE", "70.00", thisMonthAt(2), bookId);

        // 只把第二条账单逻辑删除（不动流水）→ 汇总应立即少掉 70
        jdbcTemplate.update("UPDATE t_bill SET deleted = 1 WHERE book_id = ? AND amount = 70.00", bookId);

        BillSummaryVO vo = billService.summary(MY_ID, bookId);
        System.out.printf("  删除一条 70 元后，支出 = %s%n", vo.expense());
        assertEquals(0, vo.expense().compareTo(new BigDecimal("25.00")),
                "★ 手写 SQL 里的 deleted = 0 必须生效");
        System.out.println("  ✓ 已删除账单未参与聚合");
    }

    // ==================== ★ 时间边界 ====================

    @Test
    void timeRangeIsLeftClosedRightOpen() {
        System.out.println("\n===== ★ 时间边界：左闭右开 =====");
        YearMonth m = YearMonth.now();
        LocalDateTime monthStart = m.atDay(1).atStartOfDay();          // 本月 1 日 00:00:00
        LocalDateTime nextStart = m.plusMonths(1).atDay(1).atStartOfDay();  // 次月 1 日 00:00:00
        LocalDateTime beforeStart = monthStart.minusSeconds(1);        // 上月末最后一秒

        // 用 insertRaw 绕过"不能是未来时间"的校验，才能构造月末/次月初的数据
        insertRaw("EXPENSE", "1.00", monthStart, bookId, MY_ID);       // 应【计入】（左闭）
        insertRaw("EXPENSE", "2.00", beforeStart, bookId, MY_ID);      // 应【排除】
        insertRaw("EXPENSE", "4.00", nextStart, bookId, MY_ID);        // 应【排除】（右开）
        insertRaw("EXPENSE", "8.00", nextStart.minusSeconds(1), bookId, MY_ID);  // 应【计入】

        BillSummaryVO vo = billService.summary(MY_ID, bookId);
        System.out.printf("  本月1日00:00:00=%s（计入），上月末-1s=%s（排除）%n", "1.00", "2.00");
        System.out.printf("  次月1日00:00:00=%s（排除），次月1日-1s=%s（计入）%n", "4.00", "8.00");
        System.out.printf("  → 汇总支出 = %s（应 = 1 + 8 = 9.00）%n", vo.expense());

        assertEquals(0, vo.expense().compareTo(new BigDecimal("9.00")),
                "★ 左闭右开：本月1日00:00计入、次月1日00:00排除；若得 12.00 说明用了 <= end");
        System.out.println("  ✓ 左闭右开语义正确（1 + 8 = 9.00，排除了 2.00 与 4.00）");
    }

    // ==================== 共用的月度聚合方法 ====================

    @Test
    void monthSumIsReusableAndConsistentWithSummary() {
        System.out.println("\n===== ★ monthSum 是共用口径（D7/D8 会调它）=====");
        add("EXPENSE", "25.00", thisMonthAt(1), bookId);
        add("EXPENSE", "75.00", thisMonthAt(2), bookId);
        add("INCOME", "300.00", thisMonthAt(3), bookId);

        YearMonth m = YearMonth.now();
        BigDecimal e = billService.monthSum(MY_ID, bookId, BillService.TYPE_EXPENSE, m);
        BigDecimal i = billService.monthSum(MY_ID, bookId, BillService.TYPE_INCOME, m);
        BillSummaryVO vo = billService.summary(MY_ID, bookId);

        System.out.printf("  monthSum 支出=%s 收入=%s；summary 支出=%s 收入=%s%n",
                e, i, vo.expense(), vo.income());
        assertEquals(0, e.compareTo(new BigDecimal("100.00")));
        assertEquals(0, i.compareTo(new BigDecimal("300.00")));
        // 两者必须一致 —— 这正是"禁止两处各写一份"的意义
        assertEquals(0, vo.expense().compareTo(e), "summary 与 monthSum 必须给出同一个支出数");
        assertEquals(0, vo.income().compareTo(i), "summary 与 monthSum 必须给出同一个收入数");
        System.out.println("  ✓ 两条路径结果一致（同一份 SQL，不会出现两个数字）");
    }

    @Test
    void monthSumSupportsArbitraryMonth() {
        System.out.println("\n===== monthSum 支持指定任意月份 =====");
        YearMonth last = YearMonth.now().minusMonths(1);
        add("EXPENSE", "123.45", last.atDay(10).atTime(12, 0), bookId);
        add("EXPENSE", "25.00", thisMonthAt(1), bookId);

        BigDecimal lastSum = billService.monthSum(MY_ID, bookId, BillService.TYPE_EXPENSE, last);
        BigDecimal thisSum = billService.monthSum(MY_ID, bookId, BillService.TYPE_EXPENSE, YearMonth.now());
        System.out.printf("  上月支出=%s，本月支出=%s%n", lastSum, thisSum);
        assertEquals(0, lastSum.compareTo(new BigDecimal("123.45")));
        assertEquals(0, thisSum.compareTo(new BigDecimal("25.00")));
        System.out.println("  ✓ 按传入月份统计，D7 的区间统计可以直接复用");
    }

    @Test
    void monthSumReturnsZeroForMonthWithNoData() {
        System.out.println("\n===== monthSum 空月份返回 0 =====");
        BigDecimal s = billService.monthSum(MY_ID, bookId, BillService.TYPE_EXPENSE,
                YearMonth.now().minusYears(2));
        assertNotNull(s);
        assertEquals(0, s.compareTo(BigDecimal.ZERO));
        System.out.println("  两年前那个月 → 0（不是 null）✓");
    }

    // ==================== 越权 ====================

    @Test
    void cannotSummarizeOthersBook() {
        System.out.println("\n===== 越权：不能汇总别人的账本 =====");
        Long otherBook = bookService.createDefaultBook(OTHER_ID).getId();

        BusinessException e = assertThrows(BusinessException.class,
                () -> billService.summary(MY_ID, otherBook));
        assertEquals(ErrorCode.BOOK_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  别人的账本 → 被拒(" + e.getCode() + ") ✓");

        BusinessException e2 = assertThrows(BusinessException.class,
                () -> billService.summary(MY_ID, 99999999L));
        assertEquals(ErrorCode.BOOK_NOT_FOUND.getCode(), e2.getCode());
        System.out.println("  不存在的账本 → 被拒(" + e2.getCode() + ") ✓");
    }

    @Test
    void staysConsistentThroughManyOperations() {
        System.out.println("\n===== 多次增删改后汇总仍与明细一致 =====");
        for (int i = 1; i <= 12; i++) {
            add(i % 3 == 0 ? "INCOME" : "EXPENSE", i + ".00", thisMonthAt(i), bookId);
        }
        assertMatchesDetails(billService.summary(MY_ID, bookId), "12 笔之后");

        // 删掉一笔，再核对
        Long someId = billService.pageBills(MY_ID, new BillQuery(
                bookId, null, null, null, null, null, null, 1, 1)).getRecords().get(0).id();
        billService.deleteBill(MY_ID, someId);
        assertMatchesDetails(billService.summary(MY_ID, bookId), "删除一笔之后");

        System.out.println("  ✓ 增删之后汇总始终等于明细相加");
    }
}
