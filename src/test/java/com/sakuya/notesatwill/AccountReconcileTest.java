package com.sakuya.notesatwill;

import com.sakuya.notesatwill.entity.Account;
import com.sakuya.notesatwill.entity.DTO.BillRequest;
import com.sakuya.notesatwill.entity.VO.AccountCheckVO;
import com.sakuya.notesatwill.mapper.AccountMapper;
import com.sakuya.notesatwill.service.AccountFlowService;
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
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对账自测接口（{@code AccountFlowService.reconcile}）的回归测试。
 *
 * <p>覆盖：
 * <ol>
 *   <li>正常记账后全部一致</li>
 *   <li>余额被人为改错时能查出，且 {@code diff} 正确</li>
 *   <li>无流水的账户（{@code calculated == initBalance}）</li>
 *   <li><b>金额精度：{@code 1.0} 与 {@code 1.00} 必须判为一致</b>
 *       —— 这条专门用来抓"用 {@code equals} 比 BigDecimal"的错误</li>
 *   <li>逻辑删除的流水不计入</li>
 *   <li>不串用户</li>
 *   <li>无账户时返回空列表而不报错</li>
 * </ol>
 *
 * @author sakuya
 */
@SpringBootTest
class AccountReconcileTest {

    private static final Long MY_ID = 987550001L;
    private static final Long OTHER_ID = 987550002L;
    private static final Long CAT_DINING = 1001L;
    private static final Long CAT_SALARY = 2001L;

    @Autowired
    private AccountFlowService flowService;

    @Autowired
    private BillService billService;

    @Autowired
    private BookService bookService;

    @Autowired
    private AccountMapper accountMapper;

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

    private Long newAccount(Long userId, String name, String initBalance) {
        Account a = new Account();
        a.setUserId(userId);
        a.setName(name);
        a.setType("CASH");
        a.setInitBalance(new BigDecimal(initBalance));
        a.setBalance(new BigDecimal(initBalance));
        accountMapper.insert(a);
        return a.getId();
    }

    private void record(String type, String amount, Long accountId) {
        billService.createBill(MY_ID, new BillRequest(
                bookId, "EXPENSE".equals(type) ? CAT_DINING : CAT_SALARY,
                type, new BigDecimal(amount), LocalDateTime.now(), null, accountId));
    }

    private Map<Long, AccountCheckVO> byId(Long userId) {
        return flowService.reconcile(userId).stream()
                .collect(Collectors.toMap(AccountCheckVO::accountId, Function.identity()));
    }

    private void assertAllMatched(Long userId, String scene) {
        List<AccountCheckVO> rows = flowService.reconcile(userId);
        List<String> bad = rows.stream()
                .filter(r -> !r.matched())
                .map(r -> r.name() + "(balance=" + r.balance()
                        + ", calculated=" + r.calculated() + ", diff=" + r.diff() + ")")
                .toList();
        assertTrue(bad.isEmpty(), scene + " 出现不平账户: " + bad);
    }

    // ==================== 正常情况 ====================

    @Test
    void matchedAfterNormalOperations() {
        System.out.println("\n===== 正常记账后应全部一致 =====");
        Long a1 = newAccount(MY_ID, "招行卡", "1000.00");
        Long a2 = newAccount(MY_ID, "微信零钱", "500.00");

        record("EXPENSE", "25.00", a1);    // a1: 1000 - 25 = 975
        record("EXPENSE", "30.00", a2);    // a2: 500 - 30 = 470
        record("INCOME", "200.00", a1);    // a1: 975 + 200 = 1175
        record("EXPENSE", "10.00", null);  // 未选账户，不影响任何余额

        List<AccountCheckVO> rows = flowService.reconcile(MY_ID);
        rows.forEach(r -> System.out.printf("  %s: balance=%s, calculated=%s, diff=%s, matched=%s%n",
                r.name(), r.balance(), r.calculated(), r.diff(), r.matched()));

        assertAllMatched(MY_ID, "正常操作后");
        assertEquals(2, rows.size(), "应有 2 个账户");
        Map<Long, AccountCheckVO> m = byId(MY_ID);
        assertEquals(0, m.get(a1).balance().compareTo(new BigDecimal("1175.00")),
                "1000 - 25 + 200 = 1175");
        assertEquals(0, m.get(a2).balance().compareTo(new BigDecimal("470.00")),
                "500 - 30 = 470");
        System.out.println("  ✓ 两个账户都一致，且未选账户的账单未影响余额");
    }

    // ==================== 能查出错误 ====================

    @Test
    void detectsTamperedBalanceAndReportsDiff() {
        System.out.println("\n===== 人为改错余额应被查出 =====");
        Long a1 = newAccount(MY_ID, "招行卡", "1000.00");
        record("EXPENSE", "25.00", a1);
        assertAllMatched(MY_ID, "改动前");

        // 绕过服务层，直接改库（模拟"某处代码偷偷改了余额"）
        jdbcTemplate.update("UPDATE t_account SET balance = balance - 100 WHERE id = ?", a1);

        AccountCheckVO row = byId(MY_ID).get(a1);
        System.out.printf("  改后: balance=%s, calculated=%s, diff=%s, matched=%s%n",
                row.balance(), row.calculated(), row.diff(), row.matched());

        assertFalse(row.matched(), "★ 必须查出不平");
        assertEquals(0, row.diff().compareTo(new BigDecimal("-100.00")),
                "★ diff 应为 -100（说明少了 100）");
        assertEquals(0, row.calculated().compareTo(new BigDecimal("975.00")),
                "calculated 仍按流水算出 975");
        System.out.println("  ✓ 查出不平，且 diff=-100.00 精确指出了差额");
    }

    @Test
    void accountWithoutAnyFlowIsComparedAgainstInitBalance() {
        System.out.println("\n===== 无流水的账户：calculated 应等于期初余额 =====");
        Long idle = newAccount(MY_ID, "闲置账户", "888.88");

        AccountCheckVO row = byId(MY_ID).get(idle);
        System.out.printf("  %s: balance=%s, calculated=%s, matched=%s%n",
                row.name(), row.balance(), row.calculated(), row.matched());

        assertTrue(row.matched());
        assertEquals(0, row.calculated().compareTo(new BigDecimal("888.88")));
        System.out.println("  ✓ 无流水时 calculated = initBalance，判定为一致");
    }

    // ==================== ★ 精度（抓 equals 误用）====================

    @Test
    void precisionDifferenceIsNotReportedAsMismatch() {
        System.out.println("\n===== ★ 精度：1.0 与 1.00 必须判为一致 =====");

        // 期初写成 "1.0"（1 位小数），余额写成 "1.00"（2 位小数）——
        // 数值相等，但 BigDecimal.equals 会返回 false
        Account a = new Account();
        a.setUserId(MY_ID);
        a.setName("精度测试");
        a.setType("CASH");
        a.setInitBalance(new BigDecimal("1.0"));
        a.setBalance(new BigDecimal("1.00"));
        accountMapper.insert(a);

        AccountCheckVO row = byId(MY_ID).get(a.getId());
        System.out.printf("  initBalance=%s, balance=%s, calculated=%s, diff=%s, matched=%s%n",
                "1.0", row.balance(), row.calculated(), row.diff(), row.matched());

        // 先证明这确实是 equals 会误判的情形（否则本测试没有意义）
        assertFalse(new BigDecimal("1.0").equals(new BigDecimal("1.00")),
                "前提确认：BigDecimal.equals 对 1.0 与 1.00 返回 false");
        assertEquals(0, new BigDecimal("1.0").compareTo(new BigDecimal("1.00")),
                "前提确认：compareTo 判定二者相等");

        assertTrue(row.matched(),
                "★ 必须判为一致。若此断言失败，说明实现对 BigDecimal 用了 equals");
        assertEquals(0, row.diff().compareTo(BigDecimal.ZERO));
        System.out.println("  ✓ 用 compareTo 比较，未误报（若用 equals 这里会失败）");
    }

    // ==================== 逻辑删除与隔离 ====================

    @Test
    void deletedFlowIsNotCounted() {
        System.out.println("\n===== 已逻辑删除的流水不计入 =====");
        Long a1 = newAccount(MY_ID, "招行卡", "1000.00");
        record("EXPENSE", "25.00", a1);

        // 只把流水逻辑删除，不动余额 → 对账应立刻报不平
        jdbcTemplate.update("UPDATE t_account_flow SET deleted = 1 WHERE account_id = ?", a1);

        AccountCheckVO row = byId(MY_ID).get(a1);
        System.out.printf("  流水被逻辑删除后: balance=%s, calculated=%s, diff=%s%n",
                row.balance(), row.calculated(), row.diff());

        assertFalse(row.matched(), "★ 流水不算数了，余额就不再等于 calculated");
        assertEquals(0, row.calculated().compareTo(new BigDecimal("1000.00")),
                "★ 已删除流水不计入 → calculated 回到期初值（证明 wrapper 自动带了 deleted=0）");
        System.out.println("  ✓ 已删除流水未参与聚合（@TableLogic 生效）");
    }

    @Test
    void doesNotMixOtherUsersData() {
        System.out.println("\n===== 不串用户 =====");
        Long mine = newAccount(MY_ID, "我的卡", "1000.00");
        Long others = newAccount(OTHER_ID, "别人的卡", "5000.00");
        record("EXPENSE", "25.00", mine);

        List<AccountCheckVO> myRows = flowService.reconcile(MY_ID);
        System.out.println("  MY_ID 查到 " + myRows.size() + " 个账户");
        assertEquals(1, myRows.size(), "★ 只能看到自己的账户");
        assertEquals(mine, myRows.get(0).accountId());

        List<AccountCheckVO> otherRows = flowService.reconcile(OTHER_ID);
        assertEquals(1, otherRows.size(), "别人的账户也要能正常对账");
        assertEquals(others, otherRows.get(0).accountId());
        assertTrue(otherRows.get(0).matched());
        System.out.println("  ✓ 两个用户各自只看到自己的账户");
    }

    @Test
    void deletedAccountIsNotChecked() {
        System.out.println("\n===== 已逻辑删除的账户不参与对账 =====");
        Long a1 = newAccount(MY_ID, "招行卡", "1000.00");
        Long a2 = newAccount(MY_ID, "待删除", "300.00");
        jdbcTemplate.update("UPDATE t_account SET deleted = 1 WHERE id = ?", a2);

        List<AccountCheckVO> rows = flowService.reconcile(MY_ID);
        System.out.println("  2 个账户（1 个已删除）→ 对账检查了 " + rows.size() + " 个");
        assertEquals(1, rows.size());
        assertEquals(a1, rows.get(0).accountId());
        System.out.println("  ✓ 已删除账户不参与");
    }

    @Test
    void noAccountsReturnsEmptyList() {
        System.out.println("\n===== 用户没有任何账户 =====");
        List<AccountCheckVO> rows = flowService.reconcile(MY_ID);
        assertNotNull(rows, "不应返回 null");
        assertTrue(rows.isEmpty(), "应返回空列表");
        System.out.println("  ✓ 返回空列表，不抛异常（新用户就是这状态）");
    }

    @Test
    void resultOrderIsStable() {
        System.out.println("\n===== 输出顺序稳定 =====");
        newAccount(MY_ID, "C", "1.00");
        newAccount(MY_ID, "A", "1.00");
        newAccount(MY_ID, "B", "1.00");

        List<Long> first = flowService.reconcile(MY_ID).stream()
                .map(AccountCheckVO::accountId).toList();
        List<Long> second = flowService.reconcile(MY_ID).stream()
                .map(AccountCheckVO::accountId).toList();
        System.out.println("  第一次: " + first);
        System.out.println("  第二次: " + second);
        assertEquals(first, second, "两次调用顺序必须一致，否则结果无法逐行 diff");
        System.out.println("  ✓ 顺序稳定（按 sort, id 排序）");
    }

    // ==================== 与记账联动（多步操作）====================

    @Test
    void staysMatchedAcrossMultipleOperations() {
        System.out.println("\n===== 多步操作后仍应一致（这才是对账的真正用途）=====");
        Long a1 = newAccount(MY_ID, "招行卡", "1000.00");
        Long a2 = newAccount(MY_ID, "微信", "200.00");

        for (int i = 1; i <= 10; i++) {
            record("EXPENSE", "10.00", i % 2 == 0 ? a1 : a2);
            assertAllMatched(MY_ID, "第 " + i + " 笔之后");
        }
        record("INCOME", "500.00", a1);
        assertAllMatched(MY_ID, "收入之后");

        Map<Long, AccountCheckVO> m = byId(MY_ID);
        System.out.printf("  a1: %s，a2: %s（逐笔校验 11 次全部通过）%n",
                m.get(a1).balance(), m.get(a2).balance());
        assertEquals(0, m.get(a1).balance().compareTo(new BigDecimal("1450.00")),
                "1000 - 50 + 500 = 1450");
        assertEquals(0, m.get(a2).balance().compareTo(new BigDecimal("150.00")),
                "200 - 50 = 150");
        System.out.println("  ✓ 11 次逐笔校验全部一致");
    }
}
