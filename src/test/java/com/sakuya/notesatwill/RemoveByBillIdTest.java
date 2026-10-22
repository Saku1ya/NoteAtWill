package com.sakuya.notesatwill;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Account;
import com.sakuya.notesatwill.entity.Bill;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AccountFlowService.removeByBillId} 的回归测试。
 *
 * <p>重点覆盖三件最容易出错的事：
 * <ol>
 *   <li><b>账单没有流水时必须静默返回</b>（没选账户的账单是正常情况）</li>
 *   <li><b>余额必须被反向回滚</b>，IN / OUT 两个方向都要对</li>
 *   <li><b>不能动别人的流水</b>，且重复调用应幂等</li>
 * </ol>
 *
 * <p>另外验证：调完之后<b>对账仍然通过</b> —— 这是余额正确性的最终判据。
 *
 * @author sakuya
 */
@SpringBootTest
class RemoveByBillIdTest {

    private static final Long MY_ID = 987540001L;
    private static final Long OTHER_ID = 987540002L;
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
    private Long accountId;

    @BeforeEach
    void setUp() {
        clean();
        bookId = bookService.createDefaultBook(MY_ID).getId();
        accountId = newAccount(MY_ID, "招行卡", "1000.00");
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

    private Long newAccount(Long userId, String name, String balance) {
        Account a = new Account();
        a.setUserId(userId);
        a.setName(name);
        a.setType("CASH");
        a.setInitBalance(new BigDecimal(balance));
        a.setBalance(new BigDecimal(balance));
        accountMapper.insert(a);
        return a.getId();
    }

    private Bill createBill(String type, String amount, Long accId) {
        return billService.createBill(MY_ID, new BillRequest(
                bookId, "EXPENSE".equals(type) ? CAT_DINING : CAT_SALARY,
                type, new BigDecimal(amount), LocalDateTime.now(), "测试", accId));
    }

    private BigDecimal balanceOf(Long id) {
        return jdbcTemplate.queryForObject("SELECT balance FROM t_account WHERE id = ?", BigDecimal.class, id);
    }

    private long flowCount(Long billId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_account_flow WHERE bill_id = ? AND deleted = 0", Long.class, billId);
        return n == null ? 0 : n;
    }

    private void assertReconciled(String scene) {
        List<AccountCheckVO> rows = flowService.reconcile(MY_ID);
        List<String> bad = rows.stream().filter(r -> !r.matched())
                .map(r -> r.name() + " diff=" + r.diff()).toList();
        assertTrue(bad.isEmpty(), scene + " 对账不平: " + bad);
    }

    // ==================== ★ 最重要：没有流水 ====================

    @Test
    void billWithoutFlowReturnsSilentlyWithoutTouchingBalance() {
        System.out.println("\n===== ★★ 没选账户的账单：应静默返回，不碰任何余额 =====");

        Bill plain = createBill("EXPENSE", "25.00", null);   // 没选账户 → 无流水
        BigDecimal before = balanceOf(accountId);
        System.out.println("  账单 id=" + plain.getId() + "，流水条数=" + flowCount(plain.getId()));
        System.out.println("  回滚前余额 = " + before);

        // ★ 这里绝不能抛异常（NPE 或业务异常都不行）
        flowService.removeByBillId(MY_ID, plain.getId());

        BigDecimal after = balanceOf(accountId);
        System.out.println("  回滚后余额 = " + after);
        assertEquals(0, after.compareTo(before), "★ 没流水的账单不该影响任何余额");
        assertReconciled("删除无流水账单后");
        System.out.println("  ✓ 静默返回，余额纹丝不动，未抛异常");
    }

    // ==================== 余额回滚（两个方向）====================

    @Test
    void rollsBackOutFlow() {
        System.out.println("\n===== 回滚 OUT（支出）：余额应加回去 =====");
        Bill b = createBill("EXPENSE", "25.00", accountId);   // 1000 - 25 = 975
        assertEquals(0, balanceOf(accountId).compareTo(new BigDecimal("975.00")));
        System.out.println("  记账后余额 = " + balanceOf(accountId) + "（1000 - 25）");

        flowService.removeByBillId(MY_ID, b.getId());

        BigDecimal after = balanceOf(accountId);
        System.out.println("  回滚后余额 = " + after);
        assertEquals(0, after.compareTo(new BigDecimal("1000.00")), "★ OUT 回滚应【加回】25");
        assertEquals(0, flowCount(b.getId()), "流水应被逻辑删除");
        assertReconciled("回滚 OUT 后");
        System.out.println("  ✓ 余额加回 25，流水已逻辑删除，对账通过");
    }

    @Test
    void rollsBackInFlow() {
        System.out.println("\n===== 回滚 IN（收入）：余额应减回去 =====");
        Bill b = createBill("INCOME", "300.00", accountId);   // 1000 + 300 = 1300
        assertEquals(0, balanceOf(accountId).compareTo(new BigDecimal("1300.00")));
        System.out.println("  记账后余额 = " + balanceOf(accountId) + "（1000 + 300）");

        flowService.removeByBillId(MY_ID, b.getId());

        BigDecimal after = balanceOf(accountId);
        System.out.println("  回滚后余额 = " + after);
        assertEquals(0, after.compareTo(new BigDecimal("1000.00")), "★ IN 回滚应【减去】300");
        assertReconciled("回滚 IN 后");
        System.out.println("  ✓ 余额减回 300，方向未写反");
    }

    @Test
    void rollbackIsExactForAwkwardAmounts() {
        System.out.println("\n===== 金额精度（不用 equals 比金额）=====");
        Bill b = createBill("EXPENSE", "0.01", accountId);
        flowService.removeByBillId(MY_ID, b.getId());
        BigDecimal after = balanceOf(accountId);
        System.out.println("  支出 0.01 再回滚 → " + after);
        assertEquals(0, after.compareTo(new BigDecimal("1000.00")), "一分钱也要精确回滚");

        Bill b2 = createBill("EXPENSE", "333.33", accountId);
        flowService.removeByBillId(MY_ID, b2.getId());
        System.out.println("  支出 333.33 再回滚 → " + balanceOf(accountId));
        assertEquals(0, balanceOf(accountId).compareTo(new BigDecimal("1000.00")));
        assertReconciled("多次回滚后");
        System.out.println("  ✓ 金额精确回到原值");
    }

    @Test
    void multipleRollbacksAccumulateCorrectly() {
        System.out.println("\n===== 连续回滚多笔 =====");
        Bill b1 = createBill("EXPENSE", "10.00", accountId);
        Bill b2 = createBill("EXPENSE", "20.00", accountId);
        Bill b3 = createBill("INCOME", "50.00", accountId);
        // 1000 - 10 - 20 + 50 = 1020
        assertEquals(0, balanceOf(accountId).compareTo(new BigDecimal("1020.00")));

        flowService.removeByBillId(MY_ID, b1.getId());
        flowService.removeByBillId(MY_ID, b3.getId());
        flowService.removeByBillId(MY_ID, b2.getId());

        BigDecimal after = balanceOf(accountId);
        System.out.println("  三笔全部回滚后 = " + after);
        assertEquals(0, after.compareTo(new BigDecimal("1000.00")));
        assertReconciled("连续回滚后");
        System.out.println("  ✓ 回到初始 1000，对账通过");
    }

    // ==================== 幂等与越权 ====================

    @Test
    void secondCallIsIdempotent() {
        System.out.println("\n===== 重复调用应幂等（不重复回滚）=====");
        Bill b = createBill("EXPENSE", "25.00", accountId);
        flowService.removeByBillId(MY_ID, b.getId());
        BigDecimal afterFirst = balanceOf(accountId);

        // 第二次：流水已逻辑删除 → 查不到 → 静默返回
        flowService.removeByBillId(MY_ID, b.getId());
        BigDecimal afterSecond = balanceOf(accountId);

        System.out.println("  第一次后 = " + afterFirst + "，第二次后 = " + afterSecond);
        assertEquals(0, afterSecond.compareTo(afterFirst), "★ 第二次不能再回滚一遍");
        assertEquals(0, afterSecond.compareTo(new BigDecimal("1000.00")),
                "若第二次也回滚了，会变成 1025 —— 证明删流水时必须让 deleted=0 生效");
        assertReconciled("重复调用后");
        System.out.println("  ✓ 幂等：流水查不到 → 静默返回，未重复回滚");
    }

    @Test
    void cannotRemoveOthersFlow() {
        System.out.println("\n===== 越权：不能回滚别人的流水 =====");
        // OTHER 用户自己建账本、账户、账单
        Long otherBook = bookService.createDefaultBook(OTHER_ID).getId();
        Long otherAcc = newAccount(OTHER_ID, "别人的卡", "500.00");
        Bill otherBill = billService.createBill(OTHER_ID, new BillRequest(
                otherBook, CAT_DINING, "EXPENSE", new BigDecimal("30.00"),
                LocalDateTime.now(), "别人的账", otherAcc));

        BigDecimal otherBefore = balanceOf(otherAcc);

        // 用【我的 userId】去删【别人的账单】→ 查不到 → 静默返回
        flowService.removeByBillId(MY_ID, otherBill.getId());

        BigDecimal otherAfter = balanceOf(otherAcc);
        System.out.println("  别人的余额 " + otherBefore + " → " + otherAfter);
        assertEquals(0, otherAfter.compareTo(otherBefore), "★ 不能动别人的余额");
        assertEquals(1, flowCount(otherBill.getId()), "★ 别人的流水不该被删除");
        System.out.println("  ✓ 越权尝试无效果（user_id 条件让它查不到）");
    }

    @Test
    void nullArgumentsAreRejected() {
        System.out.println("\n===== null 参数 =====");
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> flowService.removeByBillId(MY_ID, null));
        assertEquals(ErrorCode.BILL_NOT_FOUND.getCode(), e1.getCode());
        System.out.println("  billId=null → 被拒(" + e1.getCode() + ") ✓");

        BusinessException e2 = assertThrows(BusinessException.class,
                () -> flowService.removeByBillId(null, 1L));
        assertEquals(ErrorCode.BILL_NOT_FOUND.getCode(), e2.getCode());
        System.out.println("  userId=null → 被拒(" + e2.getCode() + ") ✓（不静默忽略，避免掩盖调用方 bug）");
    }

    @Test
    void nonexistentBillIdReturnsSilently() {
        System.out.println("\n===== 不存在的账单 id =====");
        BigDecimal before = balanceOf(accountId);
        flowService.removeByBillId(MY_ID, 99999999L);   // 查不到流水
        assertEquals(0, balanceOf(accountId).compareTo(before));
        System.out.println("  查不到对应流水 → 静默返回，余额不变 ✓");
    }

    // ==================== 账户已被删除的边界 ====================

    @Test
    void throwsWhenAccountIsDeletedSoNothingIsSilentlyLost() {
        System.out.println("\n===== 边界：流水还在但账户已逻辑删除 =====");
        Bill b = createBill("EXPENSE", "25.00", accountId);

        // 直接逻辑删除账户（当前 AccountService.delete 还没做"有流水禁止删除"的检查）
        jdbcTemplate.update("UPDATE t_account SET deleted = 1 WHERE id = ?", accountId);
        long flowsBefore = flowCount(b.getId());

        BusinessException e = assertThrows(BusinessException.class,
                () -> flowService.removeByBillId(MY_ID, b.getId()),
                "★ 回滚不了时应抛异常，而不是静默把流水删掉");
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND.getCode(), e.getCode());

        System.out.println("  抛异常(" + e.getCode() + ")；事务回滚后流水条数 = " + flowCount(b.getId()));
        assertEquals(flowsBefore, flowCount(b.getId()),
                "★ 关键：异常时流水【不能被删掉】—— 否则留下『流水没了、余额还是错的』");
        System.out.println("  ✓ 抛异常并整体回滚（流水未被删除）");
        System.out.println("  ⚠ 代价：用户暂时删不掉这笔账单；根治办法是 D4 补 ACCOUNT_IN_USE 检查");
    }

    // ==================== 与对账联动（真正的用途）====================

    @Test
    void reconcileStaysGreenThroughMixedOperations() {
        System.out.println("\n===== 混合操作后对账仍通过（本方法存在的意义）=====");
        Long acc2 = newAccount(MY_ID, "微信", "200.00");

        Bill b1 = createBill("EXPENSE", "25.00", accountId);
        Bill b2 = createBill("EXPENSE", "30.00", acc2);
        Bill b3 = createBill("INCOME", "100.00", accountId);
        Bill b4 = createBill("EXPENSE", "99.00", null);      // 无流水
        assertReconciled("记账后");

        flowService.removeByBillId(MY_ID, b2.getId());
        assertReconciled("回滚一笔之后");

        flowService.removeByBillId(MY_ID, b4.getId());       // 无流水，应无影响
        assertReconciled("回滚无流水账单后");

        flowService.removeByBillId(MY_ID, b1.getId());
        flowService.removeByBillId(MY_ID, b3.getId());
        assertReconciled("全部回滚后");

        System.out.printf("  最终：招行卡=%s，微信=%s%n", balanceOf(accountId), balanceOf(acc2));
        assertEquals(0, balanceOf(accountId).compareTo(new BigDecimal("1000.00")));
        assertEquals(0, balanceOf(acc2).compareTo(new BigDecimal("200.00")));
        System.out.println("  ✓ 每一步对账都通过，两个账户均回到初始值");
    }
}
