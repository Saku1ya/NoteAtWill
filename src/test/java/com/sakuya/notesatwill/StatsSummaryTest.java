package com.sakuya.notesatwill;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.DTO.BillRequest;
import com.sakuya.notesatwill.entity.VO.StatsSummaryVO;
import com.sakuya.notesatwill.service.BillService;
import com.sakuya.notesatwill.service.BookService;
import com.sakuya.notesatwill.service.StatsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区间汇总（任务 6.3）回归测试。
 *
 * <p><b>核心验收点（按重要性排序）</b>：
 * <ol>
 *   <li><b>日均的分母是「已过天数」</b>，不是区间总天数 ——
 *       查本月时用 31 天会把日均算低一半，这是本功能最隐蔽的错误</li>
 *   <li>区间两端都包含（验证左闭右开的转换）</li>
 *   <li>结余 = 收入 - 支出（可为负）</li>
 *   <li>最大单笔：金额 + 日期都要对；无支出时为 null</li>
 *   <li>与饼图/趋势口径一致</li>
 * </ol>
 *
 * @author sakuya
 */
@SpringBootTest
class StatsSummaryTest {

    private static final Long MY_ID = 987497001L;
    /** 用于「越权」用例的另一个用户；★ 它的数据也必须被清理（否则每跑一次漏一条账本） */
    private static final Long OTHER_ID = 987497002L;
    private static final Long CAT_LUNCH = 100102L;    // 餐饮/午餐
    private static final Long CAT_TAXI = 100202L;     // 交通/打车
    private static final Long CAT_SALARY = 2001L;     // 职业收入

    @Autowired
    private StatsService statsService;

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
        // ★ 必须连 OTHER_ID 一起清：越权用例会给它建账本，
        //   只清 MY_ID 会让每次运行都往库里留一条孤儿账本（实测漏了 4 条）。
        for (Long u : new Long[]{MY_ID, OTHER_ID}) {
            jdbcTemplate.update("DELETE FROM t_budget WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_account_flow WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_chat_message WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_bill WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_account WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_book WHERE user_id = ?", u);
        }
    }

    private void spend(LocalDate day, String amount, Long categoryId) {
        billService.createBill(MY_ID, new BillRequest(
                bookId, categoryId, BillService.TYPE_EXPENSE,
                new BigDecimal(amount), safeTimeOf(day), "测试", null));
    }

    private void earn(LocalDate day, String amount) {
        billService.createBill(MY_ID, new BillRequest(
                bookId, CAT_SALARY, BillService.TYPE_INCOME,
                new BigDecimal(amount), safeTimeOf(day), "收入", null));
    }

    /**
     * 给指定日期造一个<b>一定不会落在未来</b>的时刻。
     *
     * <p><b>★ 为什么不能直接写 {@code day.atTime(12, 0)}</b>：
     * 如果 {@code day} 是今天，而当前时间还没到 12:00（例如上午 10 点），
     * 那 {@code 今天 12:00} 就是未来时刻，会被 {@code BILL_TIME_FUTURE} 拒绝
     * —— 测试结果会依赖"跑测试时是几点"，时早时晚地飘。
     *
     * <p>规则：<b>今天</b>用"此刻减 1 分钟"，其它日期用 12:00（肯定已过去）。
     */
    private java.time.LocalDateTime safeTimeOf(LocalDate day) {
        if (day.equals(LocalDate.now())) {
            return java.time.LocalDateTime.now().minusMinutes(1);
        }
        return day.atTime(12, 0);
    }

    // ==================== 基础字段 ====================

    @Test
    void returnsIncomeExpenseSurplus() {
        System.out.println("\n===== 收入 / 支出 / 结余 =====");
        LocalDate d = LocalDate.now().minusDays(2);
        earn(d, "8000.00");
        spend(d, "300.00", CAT_LUNCH);
        spend(d, "200.00", CAT_TAXI);

        StatsSummaryVO vo = statsService.summary(MY_ID, bookId, null, null);

        System.out.println("  收入=" + vo.income() + " 支出=" + vo.expense()
                + " 结余=" + vo.surplus());
        assertEquals(0, vo.income().compareTo(new BigDecimal("8000.00")));
        assertEquals(0, vo.expense().compareTo(new BigDecimal("500.00")));
        assertEquals(0, vo.surplus().compareTo(new BigDecimal("7500.00")));
        System.out.println("  ✓ 8000 - 500 = 7500");
    }

    @Test
    void surplusCanBeNegative() {
        System.out.println("\n===== 结余可以为负（支出 > 收入）=====");
        LocalDate d = LocalDate.now().minusDays(1);
        earn(d, "100.00");
        spend(d, "300.00", CAT_LUNCH);

        StatsSummaryVO vo = statsService.summary(MY_ID, bookId, null, null);
        System.out.println("  收入 100 - 支出 300 = " + vo.surplus());
        assertEquals(0, vo.surplus().compareTo(new BigDecimal("-200.00")));
        System.out.println("  ✓ 结余 = -200.00，没有取绝对值");
    }

    // ==================== ★★ 日均的分母 ====================

    @Test
    void dailyAverageUsesElapsedDaysNotTotalDays() {
        System.out.println("\n===== ★★ 日均分母 = 已过天数（不是整月天数）=====");
        LocalDate today = LocalDate.now();
        YearMonth thisMonth = YearMonth.from(today);

        // 本月累计支出 1000
        spend(today, "1000.00", CAT_LUNCH);

        StatsSummaryVO vo = statsService.summary(MY_ID, bookId,
                thisMonth.atDay(1), thisMonth.atEndOfMonth());

        long elapsed = ChronoUnit.DAYS.between(thisMonth.atDay(1), today) + 1;
        long total = thisMonth.lengthOfMonth();
        BigDecimal expected = new BigDecimal("1000.00")
                .divide(BigDecimal.valueOf(elapsed), 2, java.math.RoundingMode.HALF_UP);

        System.out.printf("  今天=%s，月初=%s，整月=%d 天，已过=%d 天%n",
                today, thisMonth.atDay(1), total, elapsed);
        System.out.printf("  days=%d，日均=%s，期望=%s%n",
                vo.days(), vo.dailyAverage(), expected);
        System.out.printf("  （若误用整月 %d 天，日均会是 %s —— 严重偏低）%n",
                total, new BigDecimal("1000.00").divide(BigDecimal.valueOf(total), 2,
                        java.math.RoundingMode.HALF_UP));

        assertEquals(elapsed, vo.days(), "★ days 必须是已过天数");
        assertEquals(0, vo.dailyAverage().compareTo(expected),
                "★ 日均分母必须是已过天数");
        assertTrue(vo.days() <= total, "已过天数不可能超过整月天数");

        if (elapsed < total) {
            BigDecimal wrong = new BigDecimal("1000.00")
                    .divide(BigDecimal.valueOf(total), 2, java.math.RoundingMode.HALF_UP);
            assertTrue(vo.dailyAverage().compareTo(wrong) > 0,
                    "★ 已过天数更少 → 日均应【更高】；若相等说明分母用错了");
            System.out.println("  ✓ 日均高于「按整月算」的值，说明分母正确");
        }
    }

    @Test
    void historicalRangeUsesFullDays() {
        System.out.println("\n===== ★ 历史区间用完整天数 =====");
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        spend(lastMonth.atDay(10), "310.00", CAT_LUNCH);

        StatsSummaryVO vo = statsService.summary(MY_ID, bookId,
                lastMonth.atDay(1), lastMonth.atEndOfMonth());

        long total = lastMonth.lengthOfMonth();
        BigDecimal expected = new BigDecimal("310.00")
                .divide(BigDecimal.valueOf(total), 2, java.math.RoundingMode.HALF_UP);

        System.out.printf("  %s 共 %d 天，days=%d，日均=%s，期望=%s%n",
                lastMonth, total, vo.days(), vo.dailyAverage(), expected);
        assertEquals(total, vo.days(), "历史月已过完，days = 整月天数");
        assertEquals(0, vo.dailyAverage().compareTo(expected));
        System.out.println("  ✓ 用完整天数");
    }

    @Test
    void futureRangeHasZeroDaysAndZeroAverage() {
        System.out.println("\n===== 未来区间：days=0，日均=0（不除零）=====");
        LocalDate futureStart = LocalDate.now().plusMonths(1).withDayOfMonth(1);
        LocalDate futureEnd = futureStart.plusDays(9);

        StatsSummaryVO vo = statsService.summary(MY_ID, bookId, futureStart, futureEnd);

        System.out.println("  days=" + vo.days() + " 日均=" + vo.dailyAverage());
        assertEquals(0, vo.days());
        assertEquals(0, vo.dailyAverage().compareTo(BigDecimal.ZERO), "不能除零，也不能返回 null");
        System.out.println("  ✓ 没有 ArithmeticException");
    }

    @Test
    void singleDayRangeHasOneDay() {
        System.out.println("\n===== 单日区间 → days=1，日均 = 当天支出 =====");
        LocalDate d = LocalDate.now().minusDays(3);
        spend(d, "88.00", CAT_LUNCH);

        StatsSummaryVO vo = statsService.summary(MY_ID, bookId, d, d);

        System.out.println("  days=" + vo.days() + " 日均=" + vo.dailyAverage());
        assertEquals(1, vo.days(), "★ 单日区间含两端 = 1 天，不是 0 天");
        assertEquals(0, vo.dailyAverage().compareTo(new BigDecimal("88.00")));
        System.out.println("  ✓ 差一错误的典型位置：这里若是 0 天就会除零");
    }

    // ==================== 区间边界 ====================

    @Test
    void rangeIncludesBothEnds() {
        System.out.println("\n===== ★ 区间两端都包含 =====");
        LocalDate start = LocalDate.now().minusMonths(2).withDayOfMonth(1);
        LocalDate end = start.plusMonths(1).minusDays(1);

        spend(start, "100.00", CAT_LUNCH);              // 起点当天
        spend(end, "200.00", CAT_TAXI);                 // 终点当天
        spend(end.plusDays(1), "999.00", CAT_LUNCH);    // 区间外

        StatsSummaryVO vo = statsService.summary(MY_ID, bookId, start, end);

        System.out.println("  区间 " + start + " ~ " + end + "，支出 = " + vo.expense());
        assertEquals(0, vo.expense().compareTo(new BigDecimal("300.00")),
                "★ 起点和终点当天的都必须算进来，区间外的不能算");
        System.out.println("  ✓ 100 + 200 = 300，区间外的 999 被排除");
    }

    @Test
    void swapsReversedRange() {
        System.out.println("\n===== 起止颠倒自动交换 =====");
        LocalDate a = LocalDate.now().minusDays(10);
        LocalDate b = LocalDate.now();

        StatsSummaryVO normal = statsService.summary(MY_ID, bookId, a, b);
        StatsSummaryVO swapped = statsService.summary(MY_ID, bookId, b, a);

        assertEquals(normal.start(), swapped.start());
        assertEquals(normal.end(), swapped.end());
        System.out.println("  ✓ 颠倒后仍按 " + swapped.start() + " ~ " + swapped.end() + " 处理");
    }

    // ==================== 最大单笔 ====================

    @Test
    void findsMaxExpenseWithDate() {
        System.out.println("\n===== ★ 最大单笔：金额 + 日期都对 =====");
        LocalDate d1 = LocalDate.now().minusDays(5);
        LocalDate d2 = LocalDate.now().minusDays(3);
        LocalDate d3 = LocalDate.now().minusDays(1);

        spend(d1, "100.00", CAT_LUNCH);
        spend(d2, "1280.00", CAT_TAXI);   // ← 最大
        spend(d3, "50.00", CAT_LUNCH);

        StatsSummaryVO vo = statsService.summary(MY_ID, bookId, null, null);

        System.out.println("  最大单笔 = " + vo.maxExpense() + "，日期 = " + vo.maxExpenseDate());
        assertNotNull(vo.maxExpense());
        assertEquals(0, vo.maxExpense().compareTo(new BigDecimal("1280.00")));
        assertEquals(d2, vo.maxExpenseDate(), "★ 日期必须是那笔发生的日期");
        System.out.println("  ✓ 1280.00 @ " + vo.maxExpenseDate());
    }

    @Test
    void maxExpenseNullWhenNoExpense() {
        System.out.println("\n===== 没有支出 → maxExpense 为 null（不是 0）=====");
        LocalDate d = LocalDate.now().minusDays(1);
        earn(d, "8000.00");   // 只有收入

        StatsSummaryVO vo = statsService.summary(MY_ID, bookId, null, null);

        System.out.println("  收入=" + vo.income() + " maxExpense=" + vo.maxExpense());
        assertNull(vo.maxExpense(), "★ 无支出时应为 null，前端显示「—」而不是 0 元");
        assertNull(vo.maxExpenseDate());
        assertEquals(0, vo.expense().compareTo(BigDecimal.ZERO));
        System.out.println("  ✓ null，不是 0");
    }

    @Test
    void incomeDoesNotCountAsMaxExpense() {
        System.out.println("\n===== 最大单笔只看支出，不看收入 =====");
        LocalDate d = LocalDate.now().minusDays(1);
        spend(d, "100.00", CAT_LUNCH);
        earn(d, "8000.00");   // 金额更大但它是收入

        StatsSummaryVO vo = statsService.summary(MY_ID, bookId, null, null);

        System.out.println("  支出 100 / 收入 8000 → maxExpense = " + vo.maxExpense());
        assertEquals(0, vo.maxExpense().compareTo(new BigDecimal("100.00")),
                "★ 不能被 8000 的收入顶掉");
        System.out.println("  ✓ 只取支出中的最大值");
    }

    @Test
    void maxExpenseRespectsRange() {
        System.out.println("\n===== 最大单笔受区间限制 =====");
        LocalDate inRange = LocalDate.now().minusMonths(2).withDayOfMonth(10);
        LocalDate outOfRange = LocalDate.now().minusDays(1);

        spend(inRange, "50.00", CAT_LUNCH);
        spend(outOfRange, "9999.00", CAT_TAXI);   // 区间外的大额

        LocalDate start = inRange.withDayOfMonth(1);
        LocalDate end = inRange.plusMonths(1).minusDays(1);
        StatsSummaryVO vo = statsService.summary(MY_ID, bookId, start, end);

        System.out.println("  区间内最大 = " + vo.maxExpense() + "（区间外有 9999）");
        assertEquals(0, vo.maxExpense().compareTo(new BigDecimal("50.00")));
        System.out.println("  ✓ 区间外的 9999 没有干扰");
    }

    // ==================== 口径一致性 ====================

    @Test
    void expenseMatchesCategoryRatioSum() {
        System.out.println("\n===== ★★ 汇总的支出 = 饼图各分类之和（口径一致）=====");
        LocalDate d = LocalDate.now().minusDays(2);
        spend(d, "300.00", CAT_LUNCH);
        spend(d, "200.00", CAT_TAXI);
        spend(d, "150.00", CAT_LUNCH);

        LocalDate start = LocalDate.now().withDayOfMonth(1);
        LocalDate end = LocalDate.now();

        StatsSummaryVO vo = statsService.summary(MY_ID, bookId, start, end);
        BigDecimal ratioSum = statsService
                .categoryRatio(MY_ID, bookId, "EXPENSE", start, end, false).stream()
                .map(com.sakuya.notesatwill.entity.VO.CategoryRatioVO::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        System.out.println("  汇总支出 = " + vo.expense() + "，饼图之和 = " + ratioSum);
        assertEquals(0, vo.expense().compareTo(ratioSum),
                "★ 两个接口必须给出同一个数（都走 sumAmount）");
        System.out.println("  ✓ 一致");
    }

    @Test
    void expenseMatchesTrendTotal() {
        System.out.println("\n===== 汇总的支出 = 趋势的 total =====");
        LocalDate d = LocalDate.now().minusDays(2);
        spend(d, "300.00", CAT_LUNCH);

        LocalDate start = LocalDate.now().withDayOfMonth(1);
        LocalDate end = LocalDate.now();

        StatsSummaryVO vo = statsService.summary(MY_ID, bookId, start, end);
        BigDecimal trendTotal = statsService
                .trend(MY_ID, bookId, "EXPENSE", start, end, "DAY").total();

        System.out.println("  汇总 = " + vo.expense() + "，趋势 = " + trendTotal);
        assertEquals(0, vo.expense().compareTo(trendTotal));
        System.out.println("  ✓ 一致");
    }

    // ==================== 其它 ====================

    @Test
    void emptyRangeReturnsZeros() {
        System.out.println("\n===== 无任何账单 → 全 0 / null =====");
        StatsSummaryVO vo = statsService.summary(MY_ID, bookId, null, null);

        assertEquals(0, vo.income().compareTo(BigDecimal.ZERO));
        assertEquals(0, vo.expense().compareTo(BigDecimal.ZERO));
        assertEquals(0, vo.surplus().compareTo(BigDecimal.ZERO));
        assertEquals(0, vo.dailyAverage().compareTo(BigDecimal.ZERO));
        assertNull(vo.maxExpense());
        System.out.println("  ✓ 不报错，字段都是 0/null");
    }

    @Test
    void cannotReadOthersBook() {
        System.out.println("\n===== 越权：不能查别人账本的汇总 =====");
        Long othersBook = bookService.createDefaultBook(OTHER_ID).getId();

        BusinessException e = assertThrows(BusinessException.class,
                () -> statsService.summary(MY_ID, othersBook, null, null));
        assertEquals(ErrorCode.BOOK_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")");
    }

    @Test
    void includesBookName() {
        System.out.println("\n===== 返回账本名（前端免二次查询）=====");
        StatsSummaryVO vo = statsService.summary(MY_ID, bookId, null, null);
        assertNotNull(vo.bookName());
        System.out.println("  ✓ bookName = " + vo.bookName());
    }

    @Test
    void dailyAverageRoundsToTwoDecimals() {
        System.out.println("\n===== 日均保留两位小数（HALF_UP）=====");
        // 造一个除不尽的场景：上月支出 100，天数可能是 31/30/28
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        spend(lastMonth.atDay(15), "100.00", CAT_LUNCH);

        StatsSummaryVO vo = statsService.summary(MY_ID, bookId,
                lastMonth.atDay(1), lastMonth.atEndOfMonth());

        System.out.println("  100 / " + vo.days() + " = " + vo.dailyAverage());
        assertEquals(2, vo.dailyAverage().scale(), "必须正好两位小数");
        System.out.println("  ✓ scale = 2");
    }
}
