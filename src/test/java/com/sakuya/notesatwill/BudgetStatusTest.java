package com.sakuya.notesatwill;

import com.sakuya.notesatwill.entity.DTO.BillRequest;
import com.sakuya.notesatwill.entity.DTO.BudgetRequest;
import com.sakuya.notesatwill.entity.VO.BudgetStatusVO;
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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预算执行状态（任务 5.3 + 5.4）回归测试。
 *
 * <p>核心验收点：
 * <ol>
 *   <li><b>实时聚合</b>：新增一笔支出后 {@code used} <b>立即</b>变化（因为它不落库）</li>
 *   <li><b>分类级预算必须包含子分类</b>：挂在「餐饮」的预算要算上「餐饮/午餐」的支出</li>
 *   <li><b>预警边界</b>：达到阈值 → WARNING；<b>严格超过</b>额度 → EXCEEDED；
 *       <b>正好用满不算超支</b></li>
 *   <li>历史月份可查</li>
 *   <li>收入不计入支出预算</li>
 * </ol>
 *
 * @author sakuya
 */
@SpringBootTest
class BudgetStatusTest {

    private static final Long MY_ID = 987494001L;
    private static final Long CAT_DINING = 1001L;     // 餐饮（一级）
    private static final Long CAT_LUNCH = 100102L;    // 餐饮/午餐（二级，属于餐饮）
    private static final Long CAT_TRANSPORT = 1002L;  // 交通（一级）
    private static final Long CAT_TAXI = 100202L;     // 交通/打车（二级）
    private static final Long CAT_SALARY = 2001L;     // 职业收入

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

    /** 记一笔【本月】支出（默认挂在「餐饮/午餐」这个二级分类上）。 */
    private void spend(String amount, Long categoryId) {
        billService.createBill(MY_ID, new BillRequest(
                bookId, categoryId, BillService.TYPE_EXPENSE,
                new BigDecimal(amount), LocalDateTime.now().minusHours(1), "测试", null));
    }

    private Long createBudget(Long categoryId, String amount, Integer threshold) {
        return budgetService.create(MY_ID,
                new BudgetRequest(bookId, categoryId, new BigDecimal(amount), threshold)).id();
    }

    private BudgetStatusVO statusOf(List<BudgetStatusVO> list, Long budgetId) {
        return list.stream().filter(s -> s.budgetId().equals(budgetId)).findFirst().orElseThrow();
    }

    // ==================== 实时聚合 ====================

    @Test
    void statusReflectsNewBillImmediately() {
        System.out.println("\n===== ★ 新增支出后已用量【立即】变化 =====");
        Long budgetId = createBudget(CAT_DINING, "1000.00", 80);

        BudgetStatusVO before = statusOf(budgetService.status(MY_ID, bookId, null), budgetId);
        System.out.println("  记前 used = " + before.used());
        assertEquals(0, before.used().compareTo(BigDecimal.ZERO));

        spend("100.00", CAT_LUNCH);

        BudgetStatusVO after = statusOf(budgetService.status(MY_ID, bookId, null), budgetId);
        System.out.println("  记后 used = " + after.used() + "，剩余 = " + after.remaining());
        assertEquals(0, after.used().compareTo(new BigDecimal("100.00")),
                "★ 不存在缓存，加完账立即反映");
        assertEquals(0, after.remaining().compareTo(new BigDecimal("900.00")));
        System.out.println("  ✓ 实时生效，无需刷新任何缓存");
    }

    @Test
    void deletingBillReducesUsed() {
        System.out.println("\n===== 删除账单后已用量回退 =====");
        Long budgetId = createBudget(CAT_DINING, "1000.00", 80);
        var bill = billService.createBill(MY_ID, new BillRequest(
                bookId, CAT_LUNCH, BillService.TYPE_EXPENSE,
                new BigDecimal("300.00"), LocalDateTime.now().minusHours(1), "测试", null));

        assertEquals(0, statusOf(budgetService.status(MY_ID, bookId, null), budgetId)
                .used().compareTo(new BigDecimal("300.00")));

        billService.deleteBill(MY_ID, bill.getId());

        assertEquals(0, statusOf(budgetService.status(MY_ID, bookId, null), budgetId)
                .used().compareTo(BigDecimal.ZERO), "删除后应回到 0");
        System.out.println("  ✓ 删除账单后 used 回到 0");
    }

    // ==================== 统计范围 ====================

    @Test
    void bookLevelBudgetCountsAllExpenses() {
        System.out.println("\n===== 账本级预算统计【所有】支出 =====");
        Long budgetId = createBudget(null, "1000.00", 80);

        spend("100.00", CAT_LUNCH);
        spend("200.00", CAT_TAXI);

        BudgetStatusVO vo = statusOf(budgetService.status(MY_ID, bookId, null), budgetId);
        System.out.println("  used = " + vo.used());
        assertEquals(0, vo.used().compareTo(new BigDecimal("300.00")));
        System.out.println("  ✓ 餐饮 100 + 交通 200 = 300");
    }

    @Test
    void categoryLevelBudgetIncludesChildren() {
        System.out.println("\n===== ★★ 分类级预算必须【包含子分类】（否则挂在 L1 的预算永远是 0）=====");
        Long budgetId = createBudget(CAT_DINING, "1000.00", 80);

        // 用户实际记账选的是二级分类「餐饮/午餐」
        spend("150.00", CAT_LUNCH);

        BudgetStatusVO vo = statusOf(budgetService.status(MY_ID, bookId, null), budgetId);
        System.out.println("  预算挂在「餐饮」(1001)，账单记在「午餐」(100102) → used = " + vo.used());
        assertEquals(0, vo.used().compareTo(new BigDecimal("150.00")),
                "★ 只统计 category_id = 1001 的话这里会是 0，预算形同废设");
        System.out.println("  ✓ 子分类的支出正确计入父分类预算");
    }

    @Test
    void categoryLevelBudgetExcludesOtherCategories() {
        System.out.println("\n===== 分类级预算不统计别的分类 =====");
        Long budgetId = createBudget(CAT_DINING, "1000.00", 80);

        spend("150.00", CAT_LUNCH);      // 餐饮 → 应计入
        spend("999.00", CAT_TAXI);       // 交通 → 不应计入

        BudgetStatusVO vo = statusOf(budgetService.status(MY_ID, bookId, null), budgetId);
        System.out.println("  餐饮 150 + 交通 999 → 餐饮预算 used = " + vo.used());
        assertEquals(0, vo.used().compareTo(new BigDecimal("150.00")),
                "★ 交通的支出不能算进餐饮预算");
        System.out.println("  ✓ 只统计自己的子树");
    }

    @Test
    void incomeIsNotCountedInExpenseBudget() {
        System.out.println("\n===== 收入不计入支出预算 =====");
        Long budgetId = createBudget(null, "1000.00", 80);

        spend("100.00", CAT_LUNCH);
        billService.createBill(MY_ID, new BillRequest(
                bookId, CAT_SALARY, BillService.TYPE_INCOME,
                new BigDecimal("8000.00"), LocalDateTime.now().minusHours(1), "工资", null));

        BudgetStatusVO vo = statusOf(budgetService.status(MY_ID, bookId, null), budgetId);
        System.out.println("  支出 100 + 收入 8000 → used = " + vo.used());
        assertEquals(0, vo.used().compareTo(new BigDecimal("100.00")));
        System.out.println("  ✓ 收入被正确排除");
    }

    // ==================== 预警边界 ====================

    @Test
    void statusIsNormalBelowThreshold() {
        System.out.println("\n===== 未达阈值 → NORMAL =====");
        Long budgetId = createBudget(CAT_DINING, "1000.00", 80);
        spend("500.00", CAT_LUNCH);

        BudgetStatusVO vo = statusOf(budgetService.status(MY_ID, bookId, null), budgetId);
        System.out.println("  已用 " + vo.usedPercent() + "% / 阈值 80% → " + vo.status());
        assertEquals(BudgetService.STATUS_NORMAL, vo.status());
    }

    @Test
    void statusIsWarningAtThreshold() {
        System.out.println("\n===== 正好达到阈值 → WARNING =====");
        Long budgetId = createBudget(CAT_DINING, "1000.00", 80);
        spend("800.00", CAT_LUNCH);

        BudgetStatusVO vo = statusOf(budgetService.status(MY_ID, bookId, null), budgetId);
        System.out.println("  已用 " + vo.usedPercent() + "% / 阈值 80% → " + vo.status());
        assertEquals(BudgetService.STATUS_WARNING, vo.status(), "达到阈值应预警");
    }

    @Test
    void statusIsExceededWhenOverAmount() {
        System.out.println("\n===== 超过额度 → EXCEEDED =====");
        Long budgetId = createBudget(CAT_DINING, "1000.00", 80);
        spend("1200.00", CAT_LUNCH);

        BudgetStatusVO vo = statusOf(budgetService.status(MY_ID, bookId, null), budgetId);
        System.out.println("  已用 " + vo.used() + " / 额度 " + vo.amount()
                + "，剩余 " + vo.remaining() + " → " + vo.status());
        assertEquals(BudgetService.STATUS_EXCEEDED, vo.status());
        assertTrue(vo.remaining().compareTo(BigDecimal.ZERO) < 0, "超支后剩余必须为负");
        System.out.println("  ✓ 超支状态正确，剩余为负（" + vo.remaining() + "）");
    }

    @Test
    void exactlyFullIsWarningNotExceeded() {
        System.out.println("\n===== ★★ 边界：正好花满额度 → WARNING，不是 EXCEEDED =====");
        Long budgetId = createBudget(CAT_DINING, "1000.00", 80);
        spend("1000.00", CAT_LUNCH);

        BudgetStatusVO vo = statusOf(budgetService.status(MY_ID, bookId, null), budgetId);
        System.out.println("  已用 " + vo.used() + " = 额度 " + vo.amount()
                + "，百分比 " + vo.usedPercent() + "% → " + vo.status());

        assertEquals(0, vo.remaining().compareTo(BigDecimal.ZERO), "剩余应正好为 0");
        assertEquals(BudgetService.STATUS_WARNING, vo.status(),
                "★ 「刚好花完计划」不算超支 —— 只有严格超过才是 EXCEEDED");
        System.out.println("  ✓ 判为 WARNING（用满但未超），剩余 0");
    }

    @Test
    void customThresholdIsRespected() {
        System.out.println("\n===== 自定义阈值 50% =====");
        Long budgetId = createBudget(CAT_DINING, "1000.00", 50);
        spend("600.00", CAT_LUNCH);

        BudgetStatusVO vo = statusOf(budgetService.status(MY_ID, bookId, null), budgetId);
        System.out.println("  已用 " + vo.usedPercent() + "% / 阈值 50% → " + vo.status());
        assertEquals(BudgetService.STATUS_WARNING, vo.status());
        assertEquals(50, vo.alertThreshold());
    }

    // ==================== 历史月份 ====================

    @Test
    void canQueryHistoricalMonth() {
        System.out.println("\n===== 可查历史月份 =====");
        Long budgetId = createBudget(CAT_DINING, "1000.00", 80);

        // 记一笔【上月】的支出（用上月 15 日，避开月末边界）
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        billService.createBill(MY_ID, new BillRequest(
                bookId, CAT_LUNCH, BillService.TYPE_EXPENSE,
                new BigDecimal("250.00"), lastMonth.atDay(15).atTime(12, 0), "上月消费", null));

        // 本月应为 0
        BudgetStatusVO thisMonth = statusOf(budgetService.status(MY_ID, bookId, null), budgetId);
        assertEquals(0, thisMonth.used().compareTo(BigDecimal.ZERO), "本月不该算上月那笔");
        assertEquals(YearMonth.now().toString(), thisMonth.month());

        // 上月应为 250
        BudgetStatusVO prev = statusOf(
                budgetService.status(MY_ID, bookId, lastMonth.toString()), budgetId);
        System.out.println("  " + lastMonth + " 的 used = " + prev.used());
        assertEquals(0, prev.used().compareTo(new BigDecimal("250.00")));
        assertEquals(lastMonth.toString(), prev.month());
        System.out.println("  ✓ 月份隔离正确");
    }

    @Test
    void invalidMonthFormatIsRejected() {
        System.out.println("\n===== 月份格式错误 → 4013 =====");
        createBudget(CAT_DINING, "1000.00", 80);
        var e = org.junit.jupiter.api.Assertions.assertThrows(
                com.sakuya.notesatwill.common.exception.BusinessException.class,
                () -> budgetService.status(MY_ID, bookId, "2026/10"));
        assertEquals(com.sakuya.notesatwill.common.enums.ErrorCode.BUDGET_MONTH_INVALID.getCode(),
                e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")");
    }

    @Test
    void emptyBudgetListReturnsEmpty() {
        System.out.println("\n===== 没有预算时返回空列表（不报错）=====");
        List<BudgetStatusVO> list = budgetService.status(MY_ID, bookId, null);
        assertEquals(0, list.size());
        System.out.println("  ✓ 返回空列表");
    }
}
