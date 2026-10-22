package com.sakuya.notesatwill;

import com.sakuya.notesatwill.entity.DTO.BillRequest;
import com.sakuya.notesatwill.entity.VO.CategoryRatioVO;
import com.sakuya.notesatwill.entity.VO.TrendVO;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统计（任务 6.1 类型占比 + 6.2 趋势）回归测试。
 *
 * <p>核心验收点：
 * <ol>
 *   <li><b>占比：各分类金额相加 = 总支出</b>（不能丢数据）</li>
 *   <li>二级分类的金额要<b>上卷</b>到一级</li>
 *   <li>下钻只在下钻时返回 children</li>
 *   <li><b>趋势时间轴连续、无数据补 0</b></li>
 *   <li>三种粒度的口径一致（日之和 = 月之和）</li>
 * </ol>
 *
 * @author sakuya
 */
@SpringBootTest
class StatsServiceTest {

    private static final Long MY_ID = 987496001L;
    private static final Long CAT_DINING = 1001L;      // 餐饮（一级）
    private static final Long CAT_LUNCH = 100102L;     // 餐饮/午餐（二级）
    private static final Long CAT_DINNER = 100103L;    // 餐饮/晚餐（二级）
    private static final Long CAT_TRANSPORT = 1002L;   // 交通（一级）
    private static final Long CAT_TAXI = 100202L;      // 交通/打车（二级）
    private static final Long CAT_SALARY = 2001L;      // 职业收入

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
        jdbcTemplate.update("DELETE FROM t_budget WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_account_flow WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_chat_message WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_bill WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_account WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_book WHERE user_id = ?", MY_ID);
    }

    private void spend(LocalDate day, String amount, Long categoryId) {
        billService.createBill(MY_ID, new BillRequest(
                bookId, categoryId, BillService.TYPE_EXPENSE,
                new BigDecimal(amount), safeTime(day), "测试", null));
    }

    /**
     * 给指定日期造一个<b>一定不会落在未来</b>的时刻。
     *
     * <p><b>★★ 为什么不能直接写 {@code day.atTime(12, 0)}（这个坑已踩过三次）</b>：
     * 若 {@code day} 恰好是<b>今天</b>，而当前时间还没到 12:00（比如上午 10 点），
     * 那"今天 12:00"就是未来时刻，会被 {@code BILL_TIME_FUTURE} 拒绝。
     *
     * <p>后果不只是报错 —— 它让测试结果<b>依赖"跑测试时是几点"</b>：
     * 同一个测试上午失败、下午通过。这类 flake 极难定位，
     * 而且第一反应会误以为产品坏了（其实产品行为完全正确）。
     *
     * <p>规则：<b>今天</b>用"此刻减 1 分钟"，其它日期用 12:00（肯定已过去）。
     */
    private java.time.LocalDateTime safeTime(LocalDate day) {
        if (day.equals(LocalDate.now())) {
            return java.time.LocalDateTime.now().minusMinutes(1);
        }
        return day.atTime(12, 0);
    }

    // ==================== 6.1 类型占比 ====================

    @Test
    void ratioRollsUpChildrenToTopLevel() {
        System.out.println("\n===== ★ 二级分类金额上卷到一级 =====");
        LocalDate d = LocalDate.now().minusDays(1);
        spend(d, "100.00", CAT_LUNCH);      // 餐饮/午餐
        spend(d, "200.00", CAT_DINNER);     // 餐饮/晚餐
        spend(d, "50.00", CAT_TAXI);        // 交通/打车

        List<CategoryRatioVO> ratio = statsService.categoryRatio(
                MY_ID, bookId, "EXPENSE", null, null, false);

        for (CategoryRatioVO r : ratio) {
            System.out.printf("  %s: %s (%s%%)%n", r.categoryName(), r.amount(), r.percent());
        }

        assertEquals(2, ratio.size(), "两条一级分类：餐饮、交通");
        CategoryRatioVO dining = ratio.stream()
                .filter(r -> CAT_DINING.equals(r.categoryId())).findFirst().orElseThrow();
        CategoryRatioVO transport = ratio.stream()
                .filter(r -> CAT_TRANSPORT.equals(r.categoryId())).findFirst().orElseThrow();

        assertEquals(0, dining.amount().compareTo(new BigDecimal("300.00")),
                "★ 午餐 100 + 晚餐 200 = 餐饮 300");
        assertEquals(0, transport.amount().compareTo(new BigDecimal("50.00")));
        System.out.println("  ✓ 餐饮 300（100+200）、交通 50");
    }

    @Test
    void ratioSumsToTotalExpense() {
        System.out.println("\n===== ★★ 各分类金额相加 = 总支出（验收要求）=====");
        LocalDate d = LocalDate.now().minusDays(1);
        spend(d, "100.00", CAT_LUNCH);
        spend(d, "200.00", CAT_TAXI);
        spend(d, "33.33", CAT_DINNER);

        List<CategoryRatioVO> ratio = statsService.categoryRatio(
                MY_ID, bookId, "EXPENSE", null, null, false);

        BigDecimal sum = ratio.stream()
                .map(CategoryRatioVO::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        System.out.println("  各分类之和 = " + sum + "，期望 333.33");
        assertEquals(0, sum.compareTo(new BigDecimal("333.33")));
        System.out.println("  ✓ 一毫不少（不能因为分类被删等原因丢数据）");
    }

    @Test
    void ratioPercentagesSumTo100() {
        System.out.println("\n===== 一级百分比之和 = 100% =====");
        LocalDate d = LocalDate.now().minusDays(1);
        spend(d, "600.00", CAT_LUNCH);
        spend(d, "400.00", CAT_TAXI);

        List<CategoryRatioVO> ratio = statsService.categoryRatio(
                MY_ID, bookId, "EXPENSE", null, null, false);

        BigDecimal percentSum = ratio.stream()
                .map(CategoryRatioVO::percent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        System.out.println("  百分比之和 = " + percentSum + "%");
        assertEquals(0, percentSum.compareTo(new BigDecimal("100.00")));
        System.out.println("  ✓ 正好 100.00%");
    }

    @Test
    void ratioSortedByAmountDesc() {
        System.out.println("\n===== 按金额降序 =====");
        LocalDate d = LocalDate.now().minusDays(1);
        spend(d, "100.00", CAT_TAXI);
        spend(d, "900.00", CAT_LUNCH);

        List<CategoryRatioVO> ratio = statsService.categoryRatio(
                MY_ID, bookId, "EXPENSE", null, null, false);

        assertEquals(CAT_DINING, ratio.get(0).categoryId(), "餐饮 900 应排第一");
        assertEquals(CAT_TRANSPORT, ratio.get(1).categoryId(), "交通 100 排第二");
        System.out.println("  ✓ 顺序正确：" + ratio.get(0).categoryName() + " > " + ratio.get(1).categoryName());
    }

    @Test
    void drillDownReturnsChildren() {
        System.out.println("\n===== ★ 下钻返回二级明细 =====");
        LocalDate d = LocalDate.now().minusDays(1);
        spend(d, "100.00", CAT_LUNCH);
        spend(d, "200.00", CAT_DINNER);

        List<CategoryRatioVO> ratio = statsService.categoryRatio(
                MY_ID, bookId, "EXPENSE", null, null, true);

        CategoryRatioVO dining = ratio.stream()
                .filter(r -> CAT_DINING.equals(r.categoryId())).findFirst().orElseThrow();

        System.out.println("  餐饮(" + dining.amount() + ") 下钻：");
        dining.children().forEach(c ->
                System.out.printf("    %s: %s (%s%%)%n", c.categoryName(), c.amount(), c.percent()));

        assertEquals(2, dining.children().size(), "午餐 + 晚餐两个子项");
        BigDecimal childSum = dining.children().stream()
                .map(CategoryRatioVO::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, childSum.compareTo(dining.amount()), "子项之和应等于父级金额");
        System.out.println("  ✓ 子项之和 = 父级金额");
    }

    @Test
    void noDrillDownReturnsEmptyChildren() {
        System.out.println("\n===== 不下钻时 children 为空 =====");
        LocalDate d = LocalDate.now().minusDays(1);
        spend(d, "100.00", CAT_LUNCH);

        List<CategoryRatioVO> ratio = statsService.categoryRatio(
                MY_ID, bookId, "EXPENSE", null, null, false);

        assertTrue(ratio.get(0).children().isEmpty(), "不下钻不该返回子项（省一半数据量）");
        System.out.println("  ✓ children 为空");
    }

    @Test
    void ratioRespectsDateRange() {
        System.out.println("\n===== ★ 区间两端都包含 =====");
        // 用固定的历史区间，避开本月边界
        LocalDate start = LocalDate.now().minusMonths(2).withDayOfMonth(1);
        LocalDate end = start.plusMonths(1).minusDays(1);   // 该月最后一天

        spend(start, "111.00", CAT_LUNCH);          // 起点的当天 → 应包含
        spend(end, "222.00", CAT_TAXI);             // 终点的当天 → 应包含
        spend(end.plusDays(1), "999.00", CAT_LUNCH); // 区间外 → 不应包含

        List<CategoryRatioVO> ratio = statsService.categoryRatio(
                MY_ID, bookId, "EXPENSE", start, end, false);

        BigDecimal sum = ratio.stream()
                .map(CategoryRatioVO::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        System.out.println("  区间 " + start + " ~ " + end + "，合计 = " + sum);
        assertEquals(0, sum.compareTo(new BigDecimal("333.00")),
                "★ 起点和终点当天的账单都必须算进来，区间外的不能算");
        System.out.println("  ✓ 两端都含（111 + 222 = 333），区间外的 999 被排除");
    }

    @Test
    void ratioForIncomeUsesIncomeCategories() {
        System.out.println("\n===== 收入占比走收入分类树 =====");
        LocalDate d = LocalDate.now().minusDays(1);
        billService.createBill(MY_ID, new BillRequest(
                bookId, CAT_SALARY, BillService.TYPE_INCOME,
                new BigDecimal("8000.00"), d.atTime(12, 0), "工资", null));

        List<CategoryRatioVO> ratio = statsService.categoryRatio(
                MY_ID, bookId, "INCOME", null, null, false);

        assertEquals(1, ratio.size());
        assertEquals("职业收入", ratio.get(0).categoryName());
        System.out.println("  ✓ " + ratio.get(0).categoryName() + " " + ratio.get(0).amount());
    }

    @Test
    void emptyRatioReturnsEmptyList() {
        System.out.println("\n===== 没有支出 → 空列表 =====");
        List<CategoryRatioVO> ratio = statsService.categoryRatio(
                MY_ID, bookId, "EXPENSE", null, null, false);
        assertEquals(0, ratio.size());
        System.out.println("  ✓ 返回空列表（不报错）");
    }

    // ==================== 6.2 趋势 ====================

    @Test
    void trendFillsMissingDaysWithZero() {
        System.out.println("\n===== ★★ 趋势时间轴连续、无数据补 0 =====");
        LocalDate start = LocalDate.now().minusDays(9);   // 10 天区间
        LocalDate end = LocalDate.now();

        spend(start, "100.00", CAT_LUNCH);                 // 第 1 天
        spend(end, "50.00", CAT_TAXI);                     // 第 10 天

        TrendVO trend = statsService.trend(MY_ID, bookId, "EXPENSE", start, end, "DAY");

        System.out.println("  区间 " + start + " ~ " + end + "，点数 = " + trend.points().size());
        assertEquals(10, trend.points().size(), "★ 10 天区间必须有 10 个点");

        trend.points().forEach(p ->
                System.out.println("    " + p.period() + " → " + p.amount()));

        // 中间 8 天必须都是 0
        long zeroDays = trend.points().stream()
                .filter(p -> p.amount().compareTo(BigDecimal.ZERO) == 0)
                .count();
        assertEquals(8, zeroDays, "★ 中间 8 天都应补 0，否则折线会跳过空白期");
        System.out.println("  ✓ 10 个点连续，其中 8 个为 0");
    }

    @Test
    void trendTotalEqualsSumOfPoints() {
        System.out.println("\n===== ★ total = 各点之和 =====");
        LocalDate start = LocalDate.now().minusDays(6);
        LocalDate end = LocalDate.now();
        spend(start, "100.00", CAT_LUNCH);
        spend(start.plusDays(2), "200.00", CAT_TAXI);
        spend(end, "50.00", CAT_LUNCH);

        TrendVO trend = statsService.trend(MY_ID, bookId, "EXPENSE", start, end, "DAY");

        BigDecimal sum = trend.points().stream()
                .map(TrendVO.Point::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        System.out.println("  total = " + trend.total() + "，各点之和 = " + sum);
        assertEquals(0, trend.total().compareTo(sum));
        assertEquals(0, trend.total().compareTo(new BigDecimal("350.00")));
        System.out.println("  ✓ 一致");
    }

    @Test
    void weekGranularityGroupsByMonday() {
        System.out.println("\n===== ★ 周粒度：桶 key 是该周周一 =====");
        // 取一个跨 3 周的区间
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(14);
        spend(start, "100.00", CAT_LUNCH);

        TrendVO trend = statsService.trend(MY_ID, bookId, "EXPENSE", start, end, "WEEK");

        System.out.println("  区间 " + start + " ~ " + end + "（" + trend.points().size() + " 个周桶）");
        trend.points().forEach(p -> System.out.println("    " + p.period() + " → " + p.amount()));

        // 每个 point 的 period 必须都是周一
        for (TrendVO.Point p : trend.points()) {
            assertEquals(java.time.DayOfWeek.MONDAY, LocalDate.parse(p.period()).getDayOfWeek(),
                    "★ 周桶的 key 必须是周一（ISO 周）");
        }
        assertTrue(trend.points().size() >= 3, "两周多的区间至少 3 个周桶");
        System.out.println("  ✓ 所有 period 都是周一");
    }

    @Test
    void monthGranularityGroupsByMonth() {
        System.out.println("\n===== ★ 月粒度 =====");
        LocalDate end = LocalDate.now().minusDays(1);
        LocalDate start = end.minusMonths(2).withDayOfMonth(1);

        spend(start, "100.00", CAT_LUNCH);
        spend(end, "200.00", CAT_TAXI);

        TrendVO trend = statsService.trend(MY_ID, bookId, "EXPENSE", start, end, "MONTH");

        System.out.println("  区间 " + start + " ~ " + end + "（" + trend.points().size() + " 个月桶）");
        trend.points().forEach(p -> System.out.println("    " + p.period() + " → " + p.amount()));

        for (TrendVO.Point p : trend.points()) {
            assertTrue(p.period().matches("\\d{4}-\\d{2}"), "月份 key 必须形如 2026-10，实际: " + p.period());
            
        }
        assertTrue(trend.points().size() >= 3, "跨 3 个月至少 3 个桶");
        assertEquals(0, trend.total().compareTo(new BigDecimal("300.00")));
        System.out.println("  ✓ 月桶正确，total = 300.00");
    }

    @Test
    void allGranularitiesAgreeOnTotal() {
        System.out.println("\n===== ★★ 三种粒度的 total 必须一致 =====");
        LocalDate start = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        LocalDate end = LocalDate.now();

        spend(start, "100.00", CAT_LUNCH);
        spend(start.plusDays(10), "200.00", CAT_TAXI);
        spend(end.minusDays(1), "50.00", CAT_LUNCH);

        BigDecimal dayTotal = statsService.trend(MY_ID, bookId, "EXPENSE", start, end, "DAY").total();
        BigDecimal weekTotal = statsService.trend(MY_ID, bookId, "EXPENSE", start, end, "WEEK").total();
        BigDecimal monthTotal = statsService.trend(MY_ID, bookId, "EXPENSE", start, end, "MONTH").total();

        System.out.println("  DAY=" + dayTotal + "  WEEK=" + weekTotal + "  MONTH=" + monthTotal);
        assertEquals(0, dayTotal.compareTo(weekTotal), "日与周必须一致");
        assertEquals(0, dayTotal.compareTo(monthTotal), "日与月必须一致");
        assertEquals(0, dayTotal.compareTo(new BigDecimal("350.00")));
        System.out.println("  ✓ 一致（都从同一份按天数据聚合而来）");
    }

    @Test
    void trendPointsAreContinuous() {
        System.out.println("\n===== 日粒度点位逐日连续 =====");
        LocalDate start = LocalDate.now().minusDays(29);
        LocalDate end = LocalDate.now();

        TrendVO trend = statsService.trend(MY_ID, bookId, "EXPENSE", start, end, "DAY");

        for (int i = 1; i < trend.points().size(); i++) {
            LocalDate prev = LocalDate.parse(trend.points().get(i - 1).period());
            LocalDate cur = LocalDate.parse(trend.points().get(i).period());
            assertEquals(prev.plusDays(1), cur, "第 " + i + " 个点必须紧跟上一个点");
        }
        assertEquals(30, trend.points().size());
        System.out.println("  ✓ 30 个点逐日连续");
    }

    @Test
    void invalidGranularityIsRejected() {
        System.out.println("\n===== 非法粒度 → 1000 =====");
        var e = assertThrows(com.sakuya.notesatwill.common.exception.BusinessException.class,
                () -> statsService.trend(MY_ID, bookId, "EXPENSE", null, null, "YEAR"));
        assertEquals(com.sakuya.notesatwill.common.enums.ErrorCode.PARAM_ERROR.getCode(), e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")：" + e.getMessage());
    }

    @Test
    void startAfterEndIsSwapped() {
        System.out.println("\n===== 起止颠倒会自动交换（不报错）=====");
        LocalDate a = LocalDate.now().minusDays(5);
        LocalDate b = LocalDate.now();

        TrendVO normal = statsService.trend(MY_ID, bookId, "EXPENSE", a, b, "DAY");
        TrendVO swapped = statsService.trend(MY_ID, bookId, "EXPENSE", b, a, "DAY");

        assertEquals(normal.start(), swapped.start());
        assertEquals(normal.end(), swapped.end());
        System.out.println("  ✓ 颠倒后仍按 " + swapped.start() + " ~ " + swapped.end() + " 处理");
    }
}
