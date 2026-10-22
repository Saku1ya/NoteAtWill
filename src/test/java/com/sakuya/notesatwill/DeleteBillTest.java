package com.sakuya.notesatwill;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Account;
import com.sakuya.notesatwill.entity.Bill;
import com.sakuya.notesatwill.entity.DTO.BillRequest;
import com.sakuya.notesatwill.entity.DTO.BillQuery;
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
 * {@code BillService.deleteBill}（删除账单）的回归测试。
 *
 * <p>核心验收点（手册点名的两条）：
 * <ol>
 *   <li><b>有账户的账单：删除后余额精确恢复</b></li>
 *   <li><b>没选账户的账单：删除后所有账户余额纹丝不动</b></li>
 * </ol>
 *
 * <p>另外覆盖：逻辑删除（不是物理删除）、越权、不存在的账单、
 * 以及"流水指向的账户已被删除"时整体回滚。
 *
 * @author sakuya
 */
@SpringBootTest
class DeleteBillTest {

    private static final Long MY_ID = 987520001L;
    private static final Long OTHER_ID = 987520002L;
    private static final Long CAT_DINING = 1001L;
    private static final Long CAT_SALARY = 2001L;

    @Autowired
    private BillService billService;

    @Autowired
    private AccountFlowService flowService;

    @Autowired
    private BookService bookService;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long bookId;
    private Long accA;
    private Long accB;

    @BeforeEach
    void setUp() {
        clean();
        bookId = bookService.createDefaultBook(MY_ID).getId();
        accA = newAccount(MY_ID, "招行卡", "1000.00");
        accB = newAccount(MY_ID, "微信", "500.00");
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

    private BigDecimal bal(Long id) {
        return jdbcTemplate.queryForObject("SELECT balance FROM t_account WHERE id = ?", BigDecimal.class, id);
    }

    private void assertBal(Long id, String expected) {
        assertEquals(0, bal(id).compareTo(new BigDecimal(expected)),
                "账户 " + id + " 余额应为 " + expected + "，实际 " + bal(id));
    }

    private long activeFlows(Long billId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_account_flow WHERE bill_id = ? AND deleted = 0", Long.class, billId);
        return n == null ? 0 : n;
    }

    private long allFlows(Long billId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_account_flow WHERE bill_id = ?", Long.class, billId);
        return n == null ? 0 : n;
    }

    private Integer billDeleted(Long billId) {
        return jdbcTemplate.queryForObject("SELECT deleted FROM t_bill WHERE id = ?", Integer.class, billId);
    }

    private void assertReconciled(String scene) {
        List<AccountCheckVO> rows = flowService.reconcile(MY_ID);
        List<String> bad = rows.stream().filter(r -> !r.matched())
                .map(r -> r.name() + " diff=" + r.diff()).toList();
        assertTrue(bad.isEmpty(), scene + " 对账不平: " + bad);
    }

    private Bill make(String type, String amount, Long accId) {
        return billService.createBill(MY_ID, new BillRequest(
                bookId, "EXPENSE".equals(type) ? CAT_DINING : CAT_SALARY,
                type, new BigDecimal(amount), LocalDateTime.now(), "备注", accId));
    }

    // ==================== ★★ 两条最重要的验收点 ====================

    @Test
    void deletingBillWithAccountRestoresBalance() {
        System.out.println("\n===== ★★ 有账户的账单：删除后余额精确恢复 =====");
        Bill b = make("EXPENSE", "25.00", accA);
        assertBal(accA, "975.00");
        assertEquals("OUT", jdbcTemplate.queryForObject(
                "SELECT direction FROM t_account_flow WHERE bill_id = ? AND deleted = 0",
                String.class, b.getId()));

        billService.deleteBill(MY_ID, b.getId());

        System.out.printf("  余额 %s（删除前 975.00），有效流水=%d 条%n", bal(accA), activeFlows(b.getId()));
        assertBal(accA, "1000.00");
        assertEquals(0, activeFlows(b.getId()), "流水应被逻辑删除");
        assertReconciled("删除有账户账单后");
        System.out.println("  ✓ 余额精确恢复，流水作废，对账通过");
    }

    @Test
    void deletingBillWithoutAccountTouchesNoBalance() {
        System.out.println("\n===== ★★ 没选账户的账单：所有账户余额纹丝不动 =====");
        Bill b = make("EXPENSE", "25.00", null);
        BigDecimal a0 = bal(accA);
        BigDecimal b0 = bal(accB);
        System.out.println("  删除前：A=" + a0 + "，B=" + b0 + "（该账单没有流水）");

        billService.deleteBill(MY_ID, b.getId());

        System.out.println("  删除后：A=" + bal(accA) + "，B=" + bal(accB));
        assertBal(accA, "1000.00");
        assertBal(accB, "500.00");
        assertEquals(a0.compareTo(bal(accA)), 0);
        assertEquals(b0.compareTo(bal(accB)), 0);
        assertReconciled("删除无账户账单后");
        System.out.println("  ✓ 两个账户都未被动过 —— 手册点名的坑，此分支必须有");
    }

    @Test
    void incomeBillDeletionRollsBackIncrease() {
        System.out.println("\n===== 删除收入账单：应把增加的钱扣回去 =====");
        Bill b = make("INCOME", "300.00", accA);
        assertBal(accA, "1300.00");

        billService.deleteBill(MY_ID, b.getId());

        System.out.println("  余额 " + bal(accA));
        assertBal(accA, "1000.00");
        assertReconciled("删除收入账单后");
        System.out.println("  ✓ 收入的影响被撤销（方向没写反）");
    }

    // ==================== 逻辑删除的性质 ====================

    @Test
    void billIsLogicallyDeletedNotPhysically() {
        System.out.println("\n===== 账单是【逻辑】删除，物理行仍在 =====");
        Bill b = make("EXPENSE", "25.00", accA);

        billService.deleteBill(MY_ID, b.getId());

        Integer deleted = billDeleted(b.getId());
        System.out.println("  t_bill.deleted = " + deleted + "（行仍存在）");
        assertEquals(1, deleted, "★ 应被标记 deleted=1，而不是真的 DELETE 掉");
        System.out.println("  ✓ 逻辑删除：行保留作为审计痕迹，但查询已看不到");
    }

    @Test
    void deletedBillDisappearsFromQueriesButFlowRowRemains() {
        System.out.println("\n===== 删除后：列表查不到，但流水物理行仍在 =====");
        Bill b = make("EXPENSE", "25.00", accA);
        assertEquals(1, billService.pageBills(MY_ID, new BillQuery(
                null, null, null, null, null, null, null, 1, 20)).getTotal());

        billService.deleteBill(MY_ID, b.getId());

        long visible = billService.pageBills(MY_ID, new BillQuery(
                null, null, null, null, null, null, null, 1, 20)).getTotal();
        System.out.printf("  列表可见账单=%d 条；流水的物理行数=%d（含已作废）%n",
                visible, allFlows(b.getId()));
        assertEquals(0, visible, "@TableLogic 应让已删除账单从查询中消失");
        assertEquals(1, allFlows(b.getId()), "流水物理行应保留（逻辑删除）");
        System.out.println("  ✓ 账单不可见，流水行保留");
    }

    // ==================== 越权与不存在的账单 ====================

    @Test
    void cannotDeleteOthersBill() {
        System.out.println("\n===== 越权：不能删别人的账单 =====");
        Long otherBook = bookService.createDefaultBook(OTHER_ID).getId();
        Long otherAcc = newAccount(OTHER_ID, "别人的卡", "500.00");
        Bill otherBill = billService.createBill(OTHER_ID, new BillRequest(
                otherBook, CAT_DINING, "EXPENSE", new BigDecimal("30.00"),
                LocalDateTime.now(), "别人的", otherAcc));
        BigDecimal otherBal = bal(otherAcc);

        BusinessException e = assertThrows(BusinessException.class,
                () -> billService.deleteBill(MY_ID, otherBill.getId()));
        assertEquals(ErrorCode.BILL_NOT_FOUND.getCode(), e.getCode());

        System.out.printf("  被拒(%d)；对方账单 deleted=%d，余额 %s → %s%n",
                e.getCode(), billDeleted(otherBill.getId()), otherBal, bal(otherAcc));
        assertEquals(0, billDeleted(otherBill.getId()), "★ 别人的账单不能被删");
        assertBal(otherAcc, "470.00");
        assertEquals(1, activeFlows(otherBill.getId()), "★ 别人的流水也不能被删");
        System.out.println("  ✓ 越权被拒，对方账单与流水均未受影响");
    }

    @Test
    void deletingNonexistentBillReturnsCleanError() {
        System.out.println("\n===== 删除不存在的账单：应返回干净的 4000 =====");

        // ★ 这条专治"selectOne 返回 null 后继续 deleteById(null)"的写法 ——
        //   那样会抛一个不明的运行时异常（500），而不是 4000。
        BusinessException e = assertThrows(BusinessException.class,
                () -> billService.deleteBill(MY_ID, 99999999L));
        assertEquals(ErrorCode.BILL_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  被拒(" + e.getCode() + ") —— 是业务异常 4000，不是 500 ✓");

        BusinessException e2 = assertThrows(BusinessException.class,
                () -> billService.deleteBill(MY_ID, null));
        assertEquals(ErrorCode.BILL_NOT_FOUND.getCode(), e2.getCode());
        System.out.println("  billId=null → 被拒(" + e2.getCode() + ") ✓");
    }

    @Test
    void deletingTwiceIsRejectedTheSecondTime() {
        System.out.println("\n===== 重复删除 =====");
        Bill b = make("EXPENSE", "25.00", accA);
        billService.deleteBill(MY_ID, b.getId());
        assertBal(accA, "1000.00");

        // 第二次：账单已逻辑删除 → getOwnedBill 查不到 → 4000
        BusinessException e = assertThrows(BusinessException.class,
                () -> billService.deleteBill(MY_ID, b.getId()));
        assertEquals(ErrorCode.BILL_NOT_FOUND.getCode(), e.getCode());
        assertBal(accA, "1000.00");
        System.out.println("  第二次被拒(" + e.getCode() + ")，余额仍为 1000.00（未重复回滚）✓");
    }

    // ==================== 账户已被删除的边界 ====================

    @Test
    void rollsBackEverythingWhenAccountUnavailable() {
        System.out.println("\n===== 边界：流水指向的账户已被删除 → 整体回滚 =====");
        Bill b = make("EXPENSE", "25.00", accA);
        jdbcTemplate.update("UPDATE t_account SET deleted = 1 WHERE id = ?", accA);

        BusinessException e = assertThrows(BusinessException.class,
                () -> billService.deleteBill(MY_ID, b.getId()));
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND.getCode(), e.getCode());

        System.out.printf("  抛异常(%d) 后：账单 deleted=%d，有效流水=%d 条%n",
                e.getCode(), billDeleted(b.getId()), activeFlows(b.getId()));
        assertEquals(0, billDeleted(b.getId()),
                "★ 关键：回滚失败时账单【不能被删掉】—— 否则记录没了、余额还是错的");
        assertEquals(1, activeFlows(b.getId()), "流水也不该被删");
        System.out.println("  ✓ 事务整体回滚（账单与流水都保留）");
        System.out.println("  ⚠ 代价：用户暂时删不掉这笔账单；根治办法是 D4 补 ACCOUNT_IN_USE 检查");
    }

    // ==================== 混合场景 ====================

    @Test
    void mixedDeletionsKeepEverythingConsistent() {
        System.out.println("\n===== 混合删除后对账与余额都正确 =====");
        Bill b1 = make("EXPENSE", "25.00", accA);
        Bill b2 = make("EXPENSE", "30.00", accB);
        Bill b3 = make("INCOME", "100.00", accA);
        Bill b4 = make("EXPENSE", "99.00", null);           // 无流水
        // A: 1000 - 25 + 100 = 1075 ; B: 500 - 30 = 470
        assertBal(accA, "1075.00");
        assertBal(accB, "470.00");
        assertReconciled("删除前");

        billService.deleteBill(MY_ID, b1.getId());
        assertReconciled("删 b1 后");
        billService.deleteBill(MY_ID, b4.getId());          // 无流水
        assertReconciled("删 b4（无流水）后");
        billService.deleteBill(MY_ID, b2.getId());
        billService.deleteBill(MY_ID, b3.getId());
        assertReconciled("全部删除后");

        System.out.printf("  最终 A=%s, B=%s，可见账单=%d 条%n",
                bal(accA), bal(accB),
                billService.pageBills(MY_ID, new BillQuery(
                        null, null, null, null, null, null, null, 1, 20)).getTotal());
        assertBal(accA, "1000.00");
        assertBal(accB, "500.00");
        System.out.println("  ✓ 四笔全部删除后两个账户回到初始值，对账全程通过");
    }

    @Test
    void deleteAfterUpdateKeepsBalanceCorrect() {
        System.out.println("\n===== 先改后删（两个方法联动）=====");
        Bill b = make("EXPENSE", "25.00", accA);

        // 改成：收入 60，换到 B 账户
        billService.updateBill(MY_ID, b.getId(), new BillRequest(
                bookId, CAT_SALARY, "INCOME", new BigDecimal("60.00"),
                LocalDateTime.now(), "改过", accB));
        assertBal(accA, "1000.00");     // A 已回滚
        assertBal(accB, "560.00");      // B 扣/加了 60（收入 → +60）
        assertReconciled("改完之后");

        billService.deleteBill(MY_ID, b.getId());

        System.out.printf("  删除后 A=%s, B=%s%n", bal(accA), bal(accB));
        assertBal(accA, "1000.00");
        assertBal(accB, "500.00");
        assertReconciled("删除之后");
        System.out.println("  ✓ 改动被正确撤销，两个账户回到初始值");
    }
}
