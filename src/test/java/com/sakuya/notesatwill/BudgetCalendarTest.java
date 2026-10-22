package com.sakuya.notesatwill;

import com.sakuya.notesatwill.entity.DTO.BillRequest;
import com.sakuya.notesatwill.entity.DTO.BudgetRequest;
import com.sakuya.notesatwill.entity.VO.BudgetCalendarVO;
import com.sakuya.notesatwill.service.BillService;
import com.sakuya.notesatwill.service.BookService;
import com.sakuya.notesatwill.service.BudgetService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预算日历视图（任务 5.5）回归测试。
 *
 * <p>核心验收点：
 * <ol>
 *   <li><b>缺数据的日期补 0</b>，{@code days} 长度 = 当月天数（28~31）</li>
 *   <li><b>各天之和 = 当月已用量</b></li>
 *   <li>分类级预算的日历只统计该分类子树</li>
 *   <li>闰年 2 月是 29 天（验证不是手写天数）</li>
 * </ol>
 *
 * @author sakuya
 */
@SpringBootTest
class BudgetCalendarTest {

    private static final Long MY_ID = 987495001L;
    private static final Long CAT_DINING = 1001L;
    private static final Long CAT_LUNCH = 100102L;
    private static final Long CAT_TAXI = 100202L;

    @Autowired
    private BudgetService budgetService;

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
        jdbcTemplate.update("DELETE FROM t_budget WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_account_flow WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_chat_message WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_bill WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_account WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_book WHERE user_id = ?", MY_ID);
    }

    private Long createBudget(Long categoryId, String amount) {
        return budgetService.create(MY_ID,
                new BudgetRequest(bookId, categoryId, new BigDecimal(amount), 80)).id();
    }

    /** 在指定月的第几天记一笔支出。 */
    private void spendOn(YearMonth month, int day, String amount, Long categoryId) {
        billService.createBill(MY_ID, new BillRequest(
                bookId, categoryId, BillService.TYPE_EXPENSE,
                new BigDecimal(amount), month.atDay(day).atTime(12, 0), "测试", null));
    }

    // ==================== 补 0 与天数 ====================

    @Test
    void fillsEveryDayOfMonth() {
        System.out.println("\n===== ★ 日历必须覆盖整月，缺的日期补 0 =====");
        Long budgetId = createBudget(null, "3000.00");
        YearMonth month = YearMonth.now().minusMonths(1);   // 用上月：任意日期都不是未来，测试与今天是几号无关

        // 只在 3 天有支出
        spendOn(month, 1, "100.00", CAT_LUNCH);
        spendOn(month, 10, "50.00", CAT_LUNCH);
        spendOn(month, 20, "70.00", CAT_LUNCH);

        BudgetCalendarVO vo = budgetService.calendar(MY_ID, budgetId, month.toString());

        System.out.printf("  %s 有 %d 天，days 长度 %d%n",
                month, month.lengthOfMonth(), vo.days().size());
        assertEquals(month.lengthOfMonth(), vo.days().size(),
                "★ 必须有完整天数，不能只返回有数据的那几天");

        // 第一天和最后一天的日期要对得上
        assertEquals(month.atDay(1), vo.days().get(0).date());
        assertEquals(month.atEndOfMonth(), vo.days().get(vo.days().size() - 1).date());
        System.out.println("  ✓ 从 " + vo.days().get(0).date() + " 到 "
                + vo.days().get(vo.days().size() - 1).date() + " 连续无缺");
    }

    @Test
    void daysSumEqualsUsed() {
        System.out.println("\n===== ★ 各天之和 = 当月已用量 =====");
        Long budgetId = createBudget(null, "3000.00");
        YearMonth month = YearMonth.now().minusMonths(1);   // 用上月：任意日期都不是未来，测试与今天是几号无关

        spendOn(month, 2, "100.00", CAT_LUNCH);
        spendOn(month, 15, "200.00", CAT_TAXI);
        spendOn(month, 28, "50.00", CAT_LUNCH);

        BudgetCalendarVO vo = budgetService.calendar(MY_ID, budgetId, month.toString());

        BigDecimal sum = vo.days().stream()
                .map(BudgetCalendarVO.Day::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        System.out.println("  各天之和 = " + sum + "，used = " + vo.used());
        assertEquals(0, sum.compareTo(vo.used()), "★ 两者必须相等（验收要求）");
        assertEquals(0, vo.used().compareTo(new BigDecimal("350.00")));
        assertEquals(0, vo.remaining().compareTo(new BigDecimal("2650.00")));
        System.out.println("  ✓ 和 = used = 350.00，剩余 = " + vo.remaining());
    }

    @Test
    void missingDaysAreZeroAndHaveNoData() {
        System.out.println("\n===== 没花钱的日期：amount=0 且 hasData=false =====");
        Long budgetId = createBudget(null, "3000.00");
        YearMonth month = YearMonth.now().minusMonths(1);   // 用上月：任意日期都不是未来，测试与今天是几号无关
        spendOn(month, 5, "88.00", CAT_LUNCH);

        BudgetCalendarVO vo = budgetService.calendar(MY_ID, budgetId, month.toString());

        BudgetCalendarVO.Day day1 = vo.days().get(0);           // 1 日，无支出
        BudgetCalendarVO.Day day5 = vo.days().get(4);           // 5 日，有支出

        System.out.println("  1 日: amount=" + day1.amount() + ", hasData=" + day1.hasData());
        System.out.println("  5 日: amount=" + day5.amount() + ", hasData=" + day5.hasData());

        assertEquals(0, day1.amount().compareTo(BigDecimal.ZERO));
        assertFalse(day1.hasData(),
                "★ 补 0 的天必须标记 hasData=false，否则分不清「没花钱」和「花了 0 元」");
        assertEquals(0, day5.amount().compareTo(new BigDecimal("88.00")));
        assertTrue(day5.hasData());
        System.out.println("  ✓ hasData 能区分「没花钱」和「花了 0 元」");
    }

    @Test
    void sameDayMultipleBillsAreSummed() {
        System.out.println("\n===== 同一天多笔要合并 =====");
        Long budgetId = createBudget(null, "3000.00");
        YearMonth month = YearMonth.now().minusMonths(1);   // 用上月：任意日期都不是未来，测试与今天是几号无关

        spendOn(month, 8, "30.00", CAT_LUNCH);
        spendOn(month, 8, "20.00", CAT_TAXI);

        BudgetCalendarVO vo = budgetService.calendar(MY_ID, budgetId, month.toString());
        BudgetCalendarVO.Day day8 = vo.days().get(7);

        System.out.println("  8 日两笔 30 + 20 → " + day8.amount());
        assertEquals(0, day8.amount().compareTo(new BigDecimal("50.00")));
    }

    // ==================== 统计范围 ====================

    @Test
    void categoryBudgetCalendarOnlyCountsItsScope() {
        System.out.println("\n===== ★ 分类级预算的日历只统计自己的子树 =====");
        Long budgetId = createBudget(CAT_DINING, "1000.00");
        YearMonth month = YearMonth.now().minusMonths(1);   // 用上月：任意日期都不是未来，测试与今天是几号无关

        spendOn(month, 3, "100.00", CAT_LUNCH);   // 餐饮 → 计入
        spendOn(month, 4, "999.00", CAT_TAXI);    // 交通 → 不计入

        BudgetCalendarVO vo = budgetService.calendar(MY_ID, budgetId, month.toString());

        System.out.println("  餐饮 100 + 交通 999 → 餐饮预算日历 used = " + vo.used());
        assertEquals(0, vo.used().compareTo(new BigDecimal("100.00")),
                "★ 交通的支出不能出现在餐饮预算的日历里");

        BigDecimal sum = vo.days().stream()
                .map(BudgetCalendarVO.Day::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(vo.used()));
        System.out.println("  ✓ 只统计 100，各天之和也为 100");
    }

    @Test
    void bookLevelCalendarCountsEverything() {
        System.out.println("\n===== 账本级日历统计所有支出 =====");
        Long budgetId = createBudget(null, "1000.00");
        YearMonth month = YearMonth.now().minusMonths(1);   // 用上月：任意日期都不是未来，测试与今天是几号无关

        spendOn(month, 3, "100.00", CAT_LUNCH);
        spendOn(month, 4, "200.00", CAT_TAXI);

        BudgetCalendarVO vo = budgetService.calendar(MY_ID, budgetId, month.toString());
        assertEquals(0, vo.used().compareTo(new BigDecimal("300.00")));
        System.out.println("  ✓ used = " + vo.used());
    }

    // ==================== 月份天数正确性 ====================

    @Test
    void februaryOfLeapYearHas29Days() {
        System.out.println("\n===== ★ 闰年 2 月 = 29 天（验证不是手写天数）=====");
        Long budgetId = createBudget(null, "3000.00");

        BudgetCalendarVO leap = budgetService.calendar(MY_ID, budgetId, "2024-02");
        BudgetCalendarVO normal = budgetService.calendar(MY_ID, budgetId, "2023-02");

        System.out.println("  2024-02: " + leap.days().size() + " 天");
        System.out.println("  2023-02: " + normal.days().size() + " 天");
        assertEquals(29, leap.days().size(), "2024 是闰年");
        assertEquals(28, normal.days().size(), "2023 不是闰年");
        System.out.println("  ✓ 闰年/平年天数都正确");
    }

    @Test
    void thirtyDayMonthHas30Days() {
        System.out.println("\n===== 小月 30 天 =====");
        Long budgetId = createBudget(null, "3000.00");
        BudgetCalendarVO vo = budgetService.calendar(MY_ID, budgetId, "2026-04");
        System.out.println("  2026-04: " + vo.days().size() + " 天");
        assertEquals(30, vo.days().size());
    }

    // ==================== 空月 ====================

    @Test
    void emptyMonthStillReturnsFullDays() {
        System.out.println("\n===== 完全没支出的月份仍返回完整天数（全 0）=====");
        Long budgetId = createBudget(null, "3000.00");

        BudgetCalendarVO vo = budgetService.calendar(MY_ID, budgetId, "2025-07");

        assertEquals(31, vo.days().size());
        assertEquals(0, vo.used().compareTo(BigDecimal.ZERO));
        assertTrue(vo.days().stream().noneMatch(BudgetCalendarVO.Day::hasData));
        System.out.println("  ✓ 31 天全 0，used=0，全部 hasData=false");
    }

    @Test
    void calendarRespectsBudgetIdOwnership() {
        System.out.println("\n===== 越权：不能看别人的预算日历 =====");
        var e = org.junit.jupiter.api.Assertions.assertThrows(
                com.sakuya.notesatwill.common.exception.BusinessException.class,
                () -> budgetService.calendar(MY_ID, 99999999L, "2026-10"));
        assertEquals(com.sakuya.notesatwill.common.enums.ErrorCode.BUDGET_NOT_FOUND.getCode(),
                e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")");
    }

    @Test
    void daysAreContinuousWithoutGaps() {
        System.out.println("\n===== 日期连续无跳跃 =====");
        Long budgetId = createBudget(null, "3000.00");
        BudgetCalendarVO vo = budgetService.calendar(MY_ID, budgetId, "2026-10");

        List<BudgetCalendarVO.Day> days = vo.days();
        for (int i = 1; i < days.size(); i++) {
            assertEquals(days.get(i - 1).date().plusDays(1), days.get(i).date(),
                    "第 " + i + " 天与前一天必须相差正好 1 天");
        }
        System.out.println("  ✓ " + days.size() + " 天逐日连续");
    }
}
