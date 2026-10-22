package com.sakuya.notesatwill;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sakuya.notesatwill.entity.Account;
import com.sakuya.notesatwill.entity.Bill;
import com.sakuya.notesatwill.entity.DTO.BillQuery;
import com.sakuya.notesatwill.entity.DTO.BillRequest;
import com.sakuya.notesatwill.entity.VO.AccountCheckVO;
import com.sakuya.notesatwill.entity.VO.BillSummaryVO;
import com.sakuya.notesatwill.entity.VO.BillVO;
import com.sakuya.notesatwill.entity.VO.CategoryRatioVO;
import com.sakuya.notesatwill.entity.VO.CategoryTreeVO;
import com.sakuya.notesatwill.entity.VO.StatsSummaryVO;
import com.sakuya.notesatwill.entity.VO.TrendVO;
import com.sakuya.notesatwill.service.AccountFlowService;
import com.sakuya.notesatwill.service.AccountService;
import com.sakuya.notesatwill.service.BillService;
import com.sakuya.notesatwill.service.BookService;
import com.sakuya.notesatwill.service.CategoryService;
import com.sakuya.notesatwill.service.StatsCacheService;
import com.sakuya.notesatwill.service.StatsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>全链路集成测试</b>（D10 任务 8.2）。
 *
 * <p><b>★★ 本测试的目的：用一条用例证明"主流程真的能跑通"</b>
 * <pre>
 *   建默认账本 + 默认账户（模拟注册）
 *     → 记账（含账户 → 产生流水 → 余额变动）
 *     → 查询（分页 + 筛选）
 *     → 统计（汇总 / 占比 / 趋势）
 *     → 对账（余额与流水一致）
 *     → 删除（余额回滚 + 统计同步）
 * </pre>
 * 前面各模块都有细粒度测试，但它们各自只验证一段。
 * <b>本测试验证"它们串起来是否仍然正确"</b> ——
 * 这类问题（记账后统计没更新、余额与流水不一致）在单模块测试里看不出来。
 *
 * <p><b>★ 不用 {@code @Transactional}</b>：本测试要验证"事务真正提交后各模块仍然一致"。
 * 用 {@code @Transactional} 会让所有写操作在测试末尾回滚，
 * 虽然省事，但<b>也把"跨事务的一致性"这个最需要验证的点绕过去了</b>。
 * 所以改为在 {@code @BeforeEach}/{@code @AfterEach} 里物理清理。
 *
 * @author sakuya
 */
@SpringBootTest
class FullFlowIntegrationTest {

    private static final Long USER_ID = 987600001L;

    @Autowired
    private BookService bookService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private BillService billService;

    @Autowired
    private AccountFlowService accountFlowService;

    @Autowired
    private StatsService statsService;

    @Autowired
    private StatsCacheService cacheService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        cacheService.evictUser(USER_ID);
        for (String t : new String[]{"t_chat_message", "t_daily_greeting", "t_ai_persona",
                "t_budget", "t_account_flow", "t_bill", "t_ai_parse_log", "t_account", "t_book"}) {
            jdbcTemplate.update("DELETE FROM " + t + " WHERE user_id = ?", USER_ID);
        }
        jdbcTemplate.update("DELETE FROM t_category WHERE user_id = ?", USER_ID);
    }

    /** 从 list 里取指定账户的余额。 */
    private BigDecimal balanceOf(Long accountId) {
        return accountService.list(USER_ID).stream()
                .filter(a -> a.getId().equals(accountId))
                .map(Account::getBalance)
                .findFirst().orElseThrow();
    }

    // ========================================================================
    // ★★ 主流程
    // ========================================================================

    @Test
    void fullFlowFromSetupToStatistics() {
        System.out.println("\n" + "=".repeat(72));
        System.out.println("全链路：建账本/账户 → 记账 → 查询 → 统计 → 对账 → 删除");
        System.out.println("=".repeat(72));

        // ========== ① 建默认账本与账户（模拟注册）==========
        System.out.println("\n【① 建默认账本与账户】");
        var book = bookService.createDefaultBook(USER_ID);
        Long bookId = book.getId();
        System.out.println("  默认账本：" + book.getName() + " (id=" + bookId + ")");

        var account = accountService.createDefaultAccount(USER_ID);
        Long accountId = account.getId();
        System.out.println("  默认账户：" + account.getName() + " (id=" + accountId
                + ", 余额=" + account.getBalance() + ")");

        // ========== ② 取分类 id ==========
        System.out.println("\n【② 分类树】");
        List<CategoryTreeVO> expenseTree = categoryService.tree(USER_ID, BillService.TYPE_EXPENSE);
        assertFalse(expenseTree.isEmpty(), "应有预置支出分类");
        Long catDining = expenseTree.stream()
                .filter(c -> "餐饮".equals(c.name())).findFirst().orElseThrow().id();
        Long catTransport = expenseTree.stream()
                .filter(c -> "交通".equals(c.name())).findFirst().orElseThrow().id();
        System.out.println("  支出分类 " + expenseTree.size() + " 个一级；餐饮="
                + catDining + "，交通=" + catTransport);

        // ========== ③ 记账三笔（一笔挂账户）==========
        System.out.println("\n【③ 记账】");
        LocalDateTime t = LocalDateTime.now().minusHours(1).withNano(0);

        Bill b1 = billService.createBill(USER_ID, new BillRequest(
                bookId, catDining, "EXPENSE", new BigDecimal("100.00"), t, "午饭", accountId));
        System.out.println("  ① 餐饮 100（挂账户）  billId=" + b1.getId());

        Bill b2 = billService.createBill(USER_ID, new BillRequest(
                bookId, catTransport, "EXPENSE", new BigDecimal("50.00"), t, "打车", null));
        System.out.println("  ② 交通  50（不挂账户）billId=" + b2.getId());

        Bill b3 = billService.createBill(USER_ID, new BillRequest(
                bookId, catDining, "EXPENSE", new BigDecimal("30.00"), t, "早餐", null));
        System.out.println("  ③ 餐饮  30（不挂账户）billId=" + b3.getId());

        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_bill WHERE user_id = ? AND deleted = 0",
                Integer.class, USER_ID), "应落库 3 条");

        // ========== ④ 余额：只有挂了账户的才扣 ==========
        System.out.println("\n【④ 余额检查】");
        BigDecimal balance = balanceOf(accountId);
        System.out.println("  账户余额 = " + balance + "（初始 0 − 100 = −100）");
        assertEquals(0, balance.compareTo(new BigDecimal("-100.00")),
                "★ 只有挂了账户的 100 被扣，另两笔不碰余额");

        // ========== ⑤ 查询（分页 + 筛选）==========
        System.out.println("\n【⑤ 查询】");
        Page<BillVO> page = billService.pageBills(USER_ID,
                new BillQuery(bookId, null, null, null, null, null, null, 1, 10));
        System.out.println("  分页：total=" + page.getTotal()
                + "，本页=" + page.getRecords().size());
        assertEquals(3L, page.getTotal());
        assertEquals(b3.getId(), page.getRecords().get(0).id(), "应按时间倒序（最后记的在前）");

        Page<BillVO> diningPage = billService.pageBills(USER_ID,
                new BillQuery(bookId, null, catDining, null, null, null, null, 1, 10));
        System.out.println("  按「餐饮」筛选：total=" + diningPage.getTotal());
        assertEquals(2L, diningPage.getTotal(), "餐饮有 2 笔");

        // ========== ⑥ 列表页汇总 ==========
        System.out.println("\n【⑥ 列表页汇总】");
        BillSummaryVO listed = billService.summary(USER_ID, bookId);
        System.out.println("  本月支出 = " + listed.expense());
        assertEquals(0, listed.expense().compareTo(new BigDecimal("180.00")),
                "100 + 50 + 30 = 180");

        // ========== ⑦ 统计：汇总 ==========
        System.out.println("\n【⑦ 统计 - 汇总】");
        StatsSummaryVO summary = statsService.summary(USER_ID, bookId, null, null);
        System.out.printf("  收入=%s 支出=%s 结余=%s 日均=%s days=%d 最大单笔=%s%n",
                summary.income(), summary.expense(), summary.surplus(),
                summary.dailyAverage(), summary.days(), summary.maxExpense());
        assertEquals(0, summary.expense().compareTo(new BigDecimal("180.00")));
        assertEquals(0, summary.surplus().compareTo(new BigDecimal("-180.00")), "无收入 → 结余为负");
        assertEquals(0, summary.maxExpense().compareTo(new BigDecimal("100.00")), "最大单笔 100");

        // ========== ⑧ 统计：分类占比 ==========
        System.out.println("\n【⑧ 统计 - 占比】");
        List<CategoryRatioVO> ratio = statsService.categoryRatio(
                USER_ID, bookId, "EXPENSE", null, null, false);
        ratio.forEach(r -> System.out.printf("    %s: %s (%s%%)%n",
                r.categoryName(), r.amount(), r.percent()));
        BigDecimal ratioSum = ratio.stream().map(CategoryRatioVO::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, ratioSum.compareTo(new BigDecimal("180.00")),
                "★ 各分类之和 = 总支出（口径一致性）");

        // ========== ⑨ 统计：趋势 ==========
        System.out.println("\n【⑨ 统计 - 趋势】");
        TrendVO trend = statsService.trend(USER_ID, bookId, "EXPENSE", null, null, "DAY");
        BigDecimal trendSum = trend.points().stream().map(TrendVO.Point::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        System.out.println("  日粒度点数 = " + trend.points().size()
                + "，total = " + trend.total());
        assertEquals(0, trendSum.compareTo(trend.total()), "各点之和 = total");
        assertEquals(0, trend.total().compareTo(new BigDecimal("180.00")),
                "★ 趋势 total = 汇总支出（同一口径）");

        // ========== ⑩ 对账 ==========
        System.out.println("\n【⑩ 对账】");
        List<AccountCheckVO> checks = accountFlowService.reconcile(USER_ID);
        checks.forEach(c -> System.out.printf("    %s: 余额=%s, 计算值=%s, 差额=%s, 平=%s%n",
                c.name(), c.balance(), c.calculated(), c.diff(), c.matched()));
        assertTrue(checks.stream().allMatch(AccountCheckVO::matched),
                "★★ 余额必须与流水一致 —— 整个项目最重要的正确性保证");

        // ========== ⑪ 删除挂账户的账单 → 余额回滚 + 统计同步 ==========
        System.out.println("\n【⑪ 删除挂账户的账单】");
        billService.deleteBill(USER_ID, b1.getId());

        BigDecimal afterDel = balanceOf(accountId);
        System.out.println("  删除后余额 = " + afterDel + "（应回到 0）");
        assertEquals(0, afterDel.compareTo(BigDecimal.ZERO), "★ 余额必须回滚");

        assertTrue(accountFlowService.reconcile(USER_ID).stream()
                .allMatch(AccountCheckVO::matched), "删除后对账仍应平");

        StatsSummaryVO afterStats = statsService.summary(USER_ID, bookId, null, null);
        System.out.println("  删除后本月支出 = " + afterStats.expense() + "（180 − 100 = 80）");
        assertEquals(0, afterStats.expense().compareTo(new BigDecimal("80.00")),
                "★ 统计与账单同步（缓存也已失效）");

        System.out.println("\n" + "=".repeat(72));
        System.out.println("✓ 全链路通过：建账本/账户 → 记账 → 查询 → 统计 → 对账 → 删除");
        System.out.println("=".repeat(72));
    }

    // ========================================================================
    // 关键不变量
    // ========================================================================

    @Test
    void balanceEqualsInitPlusFlows() {
        System.out.println("\n===== ★ 不变量：balance = init + ΣIN − ΣOUT =====");
        Long book = bookService.createDefaultBook(USER_ID).getId();
        Long acc = accountService.createDefaultAccount(USER_ID).getId();
        Long expenseCat = categoryService.tree(USER_ID, "EXPENSE").get(0).id();
        Long incomeCat = categoryService.tree(USER_ID, "INCOME").get(0).id();

        for (String amt : new String[]{"100.00", "200.00", "300.00"}) {
            billService.createBill(USER_ID, new BillRequest(book, expenseCat, "EXPENSE",
                    new BigDecimal(amt), LocalDateTime.now().minusHours(1), "支出", acc));
        }
        for (String amt : new String[]{"1000.00", "500.00"}) {
            billService.createBill(USER_ID, new BillRequest(book, incomeCat, "INCOME",
                    new BigDecimal(amt), LocalDateTime.now().minusHours(1), "收入", acc));
        }

        BigDecimal expected = new BigDecimal("0")
                .subtract(new BigDecimal("600.00"))
                .add(new BigDecimal("1500.00"));
        BigDecimal actual = balanceOf(acc);
        System.out.println("  余额 = " + actual + "，期望 = " + expected);
        assertEquals(0, actual.compareTo(expected));

        var check = accountFlowService.reconcile(USER_ID).stream()
                .filter(c -> c.accountId().equals(acc)).findFirst().orElseThrow();
        assertTrue(check.matched());
        System.out.println("  ✓ 900.00 = 0 − 600 + 1500，对账通过");
    }

    @Test
    void statisticsConsistentAcrossAllEndpoints() {
        System.out.println("\n===== ★★ 四个统计口径必须给出同一个数 =====");
        Long book = bookService.createDefaultBook(USER_ID).getId();
        var trees = categoryService.tree(USER_ID, "EXPENSE");
        Long c1 = trees.get(0).id();
        Long c2 = trees.size() > 1 ? trees.get(1).id() : c1;

        billService.createBill(USER_ID, new BillRequest(book, c1, "EXPENSE",
                new BigDecimal("120.00"), LocalDateTime.now().minusHours(2), "a", null));
        billService.createBill(USER_ID, new BillRequest(book, c2, "EXPENSE",
                new BigDecimal("80.00"), LocalDateTime.now().minusHours(1), "b", null));

        BigDecimal fromSummary = statsService.summary(USER_ID, book, null, null).expense();
        BigDecimal fromRatio = statsService.categoryRatio(USER_ID, book, "EXPENSE", null, null, false)
                .stream().map(CategoryRatioVO::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fromTrend = statsService.trend(USER_ID, book, "EXPENSE", null, null, "DAY").total();
        BigDecimal fromBillSummary = billService.summary(USER_ID, book).expense();

        System.out.println("  summary=" + fromSummary + "  ratio=" + fromRatio
                + "  trend=" + fromTrend + "  billSummary=" + fromBillSummary);

        assertEquals(0, fromSummary.compareTo(fromRatio), "summary vs ratio");
        assertEquals(0, fromSummary.compareTo(fromTrend), "summary vs trend");
        assertEquals(0, fromSummary.compareTo(fromBillSummary), "summary vs billSummary");
        assertEquals(0, fromSummary.compareTo(new BigDecimal("200.00")));
        System.out.println("  ✓ 四处完全一致（200.00）");
    }

    @Test
    void guardsPreventOrphanData() {
        System.out.println("\n===== 护栏：避免产生孤儿数据 =====");
        Long book = bookService.createDefaultBook(USER_ID).getId();
        Long acc = accountService.createDefaultAccount(USER_ID).getId();
        Long cat = categoryService.tree(USER_ID, "EXPENSE").get(0).id();
        billService.createBill(USER_ID, new BillRequest(book, cat, "EXPENSE",
                new BigDecimal("10.00"), LocalDateTime.now().minusHours(1), "x", acc));

        var e1 = assertThrows(com.sakuya.notesatwill.common.exception.BusinessException.class,
                () -> bookService.delete(USER_ID, book));
        System.out.println("  · 删有账单的账本 → " + e1.getMessage());

        var e2 = assertThrows(com.sakuya.notesatwill.common.exception.BusinessException.class,
                () -> accountService.delete(USER_ID, acc));
        System.out.println("  · 删有流水的账户 → " + e2.getMessage());
        System.out.println("  ✓ 两道护栏都生效，不会留下「流水还在、账户已删」的悬空状态");
    }
}
