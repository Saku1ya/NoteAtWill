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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code BillService.updateBill}（修改账单）的回归测试。
 *
 * <p>本方法采用<b>「全删重建」</b>：撤销旧的流水与余额影响，再按新数据重建。
 * 因此测试的重点是验证它<b>一条路径覆盖了全部变更维度</b>：
 * <ol>
 *   <li><b>账户三态</b>：无→有、有→无、有→有（换账户）</li>
 *   <li><b>改金额</b>：必须是「替换」而不是「叠加」—— 最容易错的一条</li>
 *   <li><b>改类型</b>：EXPENSE↔INCOME 时流水方向要反转</li>
 *   <li><b>改时间</b>：{@code flow_time} 必须跟着变</li>
 *   <li><b>清空备注</b>：传 null 要真的写进库（依赖显式 set，不是 updateById）</li>
 * </ol>
 *
 * <p>每一步之后都断言<b>对账通过</b> —— 这是余额正确性的最终判据。
 *
 * @author sakuya
 */
@SpringBootTest
class UpdateBillTest {

    private static final Long MY_ID = 987530001L;
    private static final Long OTHER_ID = 987530002L;
    private static final Long CAT_DINING = 1001L;   // EXPENSE
    private static final Long CAT_SALARY = 2001L;   // INCOME

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

    /** 取该账单当前【有效】的 BILL 流水；没有则返回 null。 */
    private Map<String, Object> activeFlow(Long billId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, account_id, direction, amount, flow_time FROM t_account_flow"
                        + " WHERE bill_id = ? AND flow_type = 'BILL' AND deleted = 0", billId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 该账单历史上存在过的流水条数（含已逻辑删除）。 */
    private long allFlowCount(Long billId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_account_flow WHERE bill_id = ?", Long.class, billId);
        return n == null ? 0 : n;
    }

    private void assertReconciled(String scene) {
        List<AccountCheckVO> rows = flowService.reconcile(MY_ID);
        List<String> bad = rows.stream().filter(r -> !r.matched())
                .map(r -> r.name() + " diff=" + r.diff()).toList();
        assertTrue(bad.isEmpty(), scene + " 对账不平: " + bad);
    }

    /** 造一笔账单。 */
    private Bill make(String type, String amount, Long accId) {
        return billService.createBill(MY_ID, new BillRequest(
                bookId, "EXPENSE".equals(type) ? CAT_DINING : CAT_SALARY,
                type, new BigDecimal(amount), LocalDateTime.now(), "原始备注", accId));
    }

    /** 构造"改这笔账单"的请求；只传想让其成为最终状态的字段。 */
    private BillRequest req(String type, String amount, Long accId, String remark) {
        return new BillRequest(bookId, "EXPENSE".equals(type) ? CAT_DINING : CAT_SALARY,
                type, new BigDecimal(amount), LocalDateTime.now(), remark, accId);
    }

    // ==================== ★ 账户三态 ====================

    @Test
    void noneToSome_createsFlowAndDeducts() {
        System.out.println("\n===== 账户：无 → 有 =====");
        Bill b = make("EXPENSE", "25.00", null);           // 没选账户
        assertBal(accA, "1000.00");
        assertNull(activeFlow(b.getId()), "此时不该有流水");

        billService.updateBill(MY_ID, b.getId(), req("EXPENSE", "25.00", accA, "原始备注"));

        Map<String, Object> f = activeFlow(b.getId());
        System.out.printf("  余额 %s，流水=%s%n", bal(accA), f);
        assertBal(accA, "975.00");
        assertNotNull(f, "★ 应补建一条流水");
        assertEquals("OUT", f.get("direction"));
        assertReconciled("无→有之后");
        System.out.println("  ✓ 补建流水并扣减 25");
    }

    @Test
    void someToNone_rollsBackBalance() {
        System.out.println("\n===== 账户：有 → 无（取消账户关联）=====");
        Bill b = make("EXPENSE", "25.00", accA);
        assertBal(accA, "975.00");

        billService.updateBill(MY_ID, b.getId(), req("EXPENSE", "25.00", null, "原始备注"));

        System.out.printf("  余额 %s，有效流水=%s%n", bal(accA), activeFlow(b.getId()));
        assertBal(accA, "1000.00");
        assertNull(activeFlow(b.getId()), "★ 流水应被逻辑删除");
        assertReconciled("有→无之后");
        System.out.println("  ✓ 余额回滚到 1000，流水作废 —— 这一步最容易漏");
    }

    @Test
    void someToSome_switchesAccount() {
        System.out.println("\n===== 账户：有 → 有（换账户）=====");
        Bill b = make("EXPENSE", "25.00", accA);
        assertBal(accA, "975.00");

        billService.updateBill(MY_ID, b.getId(), req("EXPENSE", "25.00", accB, "原始备注"));

        Map<String, Object> f = activeFlow(b.getId());
        System.out.printf("  A=%s（应回 1000），B=%s（应扣 25），新流水 account_id=%s%n",
                bal(accA), bal(accB), f.get("account_id"));
        assertBal(accA, "1000.00");
        assertBal(accB, "475.00");
        assertEquals(accB, ((Number) f.get("account_id")).longValue(), "新流水应指向 B");
        assertReconciled("换账户之后");
        System.out.println("  ✓ 旧账户加回、新账户扣减，两边都对");
    }

    // ==================== ★ 改金额：替换而非叠加 ====================

    @Test
    void changingAmountReplacesInsteadOfAccumulating() {
        System.out.println("\n===== ★ 改金额：必须是『替换』不是『叠加』=====");
        Bill b = make("EXPENSE", "25.00", accA);
        assertBal(accA, "975.00");        // 1000 - 25

        billService.updateBill(MY_ID, b.getId(), req("EXPENSE", "30.00", accA, "原始备注"));

        // 正确：1000 - 30 = 970
        // 错误（叠加）：1000 - 25 - 30 = 945，或 975 - 30 = 945
        System.out.printf("  25 元改成 30 元 → 余额 = %s（正确 970.00；若为 945.00 说明叠加了）%n", bal(accA));
        assertBal(accA, "970.00");
        assertReconciled("改金额之后");
        System.out.println("  ✓ 是替换：1000 - 30 = 970");
    }

    @Test
    void changingAmountUpAndDownReturnsToExactValue() {
        System.out.println("\n===== 金额反复改动能精确回到原值 =====");
        Bill b = make("EXPENSE", "25.00", accA);

        for (String amt : new String[]{"30.00", "0.01", "999.99", "25.00"}) {
            billService.updateBill(MY_ID, b.getId(), req("EXPENSE", amt, accA, "原始备注"));
            assertReconciled("改为 " + amt + " 后");
        }
        assertBal(accA, "975.00");   // 回到最初的 25
        System.out.println("  连续改 30 → 0.01 → 999.99 → 25，余额回到 975.00 ✓");
    }

    // ==================== ★ 改类型：方向反转 ====================

    @Test
    void changingTypeFlipsDirection() {
        System.out.println("\n===== ★ 改类型：direction 必须反转 =====");
        Bill b = make("EXPENSE", "25.00", accA);
        assertEquals("OUT", activeFlow(b.getId()).get("direction"));
        assertBal(accA, "975.00");

        // EXPENSE → INCOME（分类必须同步换成收入类分类）
        billService.updateBill(MY_ID, b.getId(), req("INCOME", "25.00", accA, "原始备注"));

        Map<String, Object> f = activeFlow(b.getId());
        System.out.printf("  新 direction=%s，余额=%s（支出时是 975，收入应变 1025）%n",
                f.get("direction"), bal(accA));
        assertEquals("IN", f.get("direction"), "★ 方向必须反转");
        assertBal(accA, "1025.00");
        assertReconciled("改类型之后");
        System.out.println("  ✓ direction 反转为 IN，余额 +25");
    }

    @Test
    void changingTypeBackAndForth() {
        System.out.println("\n===== 类型来回改 =====");
        Bill b = make("EXPENSE", "100.00", accA);
        assertBal(accA, "900.00");

        billService.updateBill(MY_ID, b.getId(), req("INCOME", "100.00", accA, "原始备注"));
        assertBal(accA, "1100.00");
        assertReconciled("改成收入后");

        billService.updateBill(MY_ID, b.getId(), req("EXPENSE", "100.00", accA, "原始备注"));
        assertBal(accA, "900.00");
        assertReconciled("改回支出后");
        System.out.println("  支出→收入→支出：900 → 1100 → 900 ✓");
    }

    // ==================== 改时间：flow_time 同步 ====================

    @Test
    void changingBillTimeUpdatesFlowTime() {
        System.out.println("\n===== 改记账时间：flow_time 必须同步 =====");
        Bill b = make("EXPENSE", "25.00", accA);
        LocalDateTime newTime = LocalDateTime.now().minusDays(3).withNano(0);

        billService.updateBill(MY_ID, b.getId(), new BillRequest(
                bookId, CAT_DINING, "EXPENSE", new BigDecimal("25.00"),
                newTime, "原始备注", accA));

        String billTime = jdbcTemplate.queryForObject(
                "SELECT bill_time FROM t_bill WHERE id = ?", String.class, b.getId());
        Object flowTime = activeFlow(b.getId()).get("flow_time");
        System.out.println("  bill_time = " + billTime);
        System.out.println("  flow_time = " + flowTime);
        assertEquals(billTime, String.valueOf(flowTime).replace('T', ' ').substring(0, 19),
                "★ 两者必须一致（列注释要求），否则按时间筛选与对账会错位");
        System.out.println("  ✓ flow_time 跟随 bill_time 更新");
    }

    // ==================== 备注 / source / 其它字段 ====================

    @Test
    void canClearRemarkToNull() {
        System.out.println("\n===== ★ 清空备注（验证用的是显式 set 而非 updateById）=====");
        Bill b = make("EXPENSE", "25.00", accA);
        assertEquals("原始备注", jdbcTemplate.queryForObject(
                "SELECT remark FROM t_bill WHERE id = ?", String.class, b.getId()));

        billService.updateBill(MY_ID, b.getId(), req("EXPENSE", "25.00", accA, null));

        String after = jdbcTemplate.queryForObject(
                "SELECT remark FROM t_bill WHERE id = ?", String.class, b.getId());
        System.out.println("  备注：原始备注 → " + after);
        assertNull(after, "★ 备注应被真正清成 NULL。若仍为『原始备注』，说明用了 updateById（会跳过 null）");
        System.out.println("  ✓ 备注被真正清空（显式 set 生效）");
    }

    @Test
    void sourceIsNotModified() {
        System.out.println("\n===== source 不应被修改 =====");
        Bill b = make("EXPENSE", "25.00", accA);
        jdbcTemplate.update("UPDATE t_bill SET source = 'AI' WHERE id = ?", b.getId());

        billService.updateBill(MY_ID, b.getId(), req("EXPENSE", "30.00", accA, "改过"));

        String src = jdbcTemplate.queryForObject(
                "SELECT source FROM t_bill WHERE id = ?", String.class, b.getId());
        System.out.println("  source = " + src);
        assertEquals("AI", src, "★ 修改不该把 AI 记的账变成手动账（会污染 AI 采纳率统计）");
        System.out.println("  ✓ source 保持 AI 不变");
    }

    @Test
    void onlyOneActiveFlowAfterManyUpdates() {
        System.out.println("\n===== 反复修改后仍只有 1 条有效流水 =====");
        Bill b = make("EXPENSE", "10.00", accA);
        for (int i = 2; i <= 6; i++) {
            billService.updateBill(MY_ID, b.getId(), req("EXPENSE", i + ".00", accA, "第" + i + "次"));
        }

        long active = activeFlow(b.getId()) == null ? 0 : 1;
        System.out.printf("  改 5 次后：有效流水=%d 条，历史流水共=%d 条（含已作废）%n",
                active, allFlowCount(b.getId()));
        assertEquals(1, active, "★ 永远只能有 1 条有效流水，否则对账会重复计算");
        assertEquals(6, allFlowCount(b.getId()), "历史流水应保留（逻辑删除，作为审计痕迹）");
        assertBal(accA, "994.00");   // 1000 - 6
        assertReconciled("反复修改后");
        System.out.println("  ✓ 1 条有效 + 5 条已作废，余额精确 994.00");
    }

    // ==================== 越权与错误 ====================

    @Test
    void cannotUpdateOthersBill() {
        System.out.println("\n===== 越权：不能改别人的账单 =====");
        Long otherBook = bookService.createDefaultBook(OTHER_ID).getId();
        Bill otherBill = billService.createBill(OTHER_ID, new BillRequest(
                otherBook, CAT_DINING, "EXPENSE", new BigDecimal("25.00"),
                LocalDateTime.now(), "别人的", null));

        BusinessException e = assertThrows(BusinessException.class,
                () -> billService.updateBill(MY_ID, otherBill.getId(),
                        req("EXPENSE", "999.00", accA, "试图篡改")));
        assertEquals(ErrorCode.BILL_NOT_FOUND.getCode(), e.getCode());

        String amt = jdbcTemplate.queryForObject(
                "SELECT amount FROM t_bill WHERE id = ?", String.class, otherBill.getId());
        System.out.println("  被拒(" + e.getCode() + ")，对方账单金额仍为 " + amt);
        System.out.println("  ✓ 越权被拒，数据未变");
    }

    @Test
    void invalidInputIsRejectedAndNothingChanges() {
        System.out.println("\n===== 非法入参：应整体拒绝，不留半成品 =====");
        Bill b = make("EXPENSE", "25.00", accA);
        BigDecimal before = bal(accA);

        // 分类 type 与账单 type 不一致
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> billService.updateBill(MY_ID, b.getId(), new BillRequest(
                        bookId, CAT_SALARY, "EXPENSE", new BigDecimal("50.00"),
                        LocalDateTime.now(), "非法", accB)));
        assertEquals(ErrorCode.CATEGORY_TYPE_MISMATCH.getCode(), e1.getCode());
        assertBal(accA, "975.00");
        assertBal(accB, "500.00");
        System.out.println("  分类/类型不一致 → 被拒(" + e1.getCode() + ")，两个账户余额均未变");

        // 未来时间
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> billService.updateBill(MY_ID, b.getId(), new BillRequest(
                        bookId, CAT_DINING, "EXPENSE", new BigDecimal("50.00"),
                        LocalDateTime.now().plusDays(2), "未来", accB)));
        assertEquals(ErrorCode.BILL_TIME_FUTURE.getCode(), e2.getCode());

        // 账户不存在
        BusinessException e3 = assertThrows(BusinessException.class,
                () -> billService.updateBill(MY_ID, b.getId(),
                        req("EXPENSE", "50.00", 99999999L, "坏账户")));
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND.getCode(), e3.getCode());

        assertBal(accA, "975.00");
        assertBal(accB, "500.00");
        assertEquals(0, bal(accA).compareTo(before));
        assertReconciled("三次非法修改被拒后");
        System.out.println("  ✓ 三次非法请求全部被拒，余额与流水完全回到修改前（事务回滚完整）");
    }

    @Test
    void amountInBillIsActuallyUpdated() {
        System.out.println("\n===== 账单本身确实被更新（不是插入新行）=====");
        Bill b = make("EXPENSE", "25.00", accA);
        long countBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_bill WHERE user_id = ?", Long.class, MY_ID);

        billService.updateBill(MY_ID, b.getId(), req("EXPENSE", "30.00", accA, "改过的备注"));

        long countAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_bill WHERE user_id = ?", Long.class, MY_ID);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT amount, remark FROM t_bill WHERE id = ?", b.getId());

        System.out.printf("  账单总数 %d → %d，该行 amount=%s, remark=%s%n",
                countBefore, countAfter, row.get("amount"), row.get("remark"));
        assertEquals(countBefore, countAfter, "★ 账单总数不能变 —— 变多说明误用了 insert");
        assertEquals(0, ((BigDecimal) row.get("amount")).compareTo(new BigDecimal("30.00")));
        assertEquals("改过的备注", row.get("remark"));
        System.out.println("  ✓ 是 UPDATE 同一行，没有产生新账单");
    }

    @Test
    void mixedTransitionsKeepReconcileGreen() {
        System.out.println("\n===== 连续多种变更后对账仍通过 =====");
        Bill b = make("EXPENSE", "25.00", null);              // 无
        billService.updateBill(MY_ID, b.getId(), req("EXPENSE", "25.00", accA, "x"));   // 无→有
        assertReconciled("无→有");
        billService.updateBill(MY_ID, b.getId(), req("INCOME", "40.00", accA, "x"));    // 改类型+金额
        assertReconciled("改类型+金额");
        billService.updateBill(MY_ID, b.getId(), req("INCOME", "40.00", accB, "x"));    // 换账户
        assertReconciled("换账户");
        billService.updateBill(MY_ID, b.getId(), req("INCOME", "40.00", null, "x"));    // 有→无
        assertReconciled("有→无");

        System.out.printf("  最终 A=%s, B=%s%n", bal(accA), bal(accB));
        assertBal(accA, "1000.00");
        assertBal(accB, "500.00");
        System.out.println("  ✓ 四步变更后两个账户都回到初始值，对账全程通过");
    }
}
