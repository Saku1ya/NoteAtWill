package com.sakuya.notesatwill;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Account;
import com.sakuya.notesatwill.entity.DTO.AdjustRequest;
import com.sakuya.notesatwill.entity.DTO.TransferRequest;
import com.sakuya.notesatwill.entity.VO.AccountCheckVO;
import com.sakuya.notesatwill.entity.VO.AccountFlowVO;
import com.sakuya.notesatwill.mapper.AccountMapper;
import com.sakuya.notesatwill.service.AccountFlowService;
import com.sakuya.notesatwill.service.AccountService;
import com.sakuya.notesatwill.service.BookService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4 账户模块（转账 / 撤销 / 校正 / 流水列表）的回归测试。
 *
 * <p>核心验收点：
 * <ol>
 *   <li>转账后两账户<b>一增一减、总额不变</b>；两条流水 {@code transfer_no} 相同</li>
 *   <li>撤销后两账户都恢复原值，<b>不残留半条</b></li>
 *   <li>校正后 <b>对账仍能通过</b>（证明写了流水）</li>
 *   <li>三类来源（BILL / TRANSFER / ADJUST）在流水列表里都能正确展示</li>
 *   <li>有流水的账户不能删除（{@code ACCOUNT_IN_USE 3024}）</li>
 * </ol>
 *
 * @author sakuya
 */
@SpringBootTest
class AccountTransferTest {

    private static final Long MY_ID = 987500001L;
    private static final Long OTHER_ID = 987500002L;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountFlowService flowService;

    @Autowired
    private BookService bookService;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long accA;
    private Long accB;

    @BeforeEach
    void setUp() {
        clean();
        accA = newAccount(MY_ID, "招行卡", "1000.00");
        accB = newAccount(MY_ID, "微信零钱", "200.00");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (Long u : new Long[]{MY_ID, OTHER_ID}) {
            jdbcTemplate.update("DELETE FROM t_account_flow WHERE user_id = ?", u);
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

    private long activeFlowCount(Long accountId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_account_flow WHERE account_id = ? AND deleted = 0",
                Long.class, accountId);
        return n == null ? 0 : n;
    }

    private void assertReconciled(String scene) {
        List<AccountCheckVO> rows = flowService.reconcile(MY_ID);
        List<String> bad = rows.stream().filter(r -> !r.matched())
                .map(r -> r.name() + " diff=" + r.diff()).toList();
        assertTrue(bad.isEmpty(), scene + " 对账不平: " + bad);
    }

    private String transfer(String amount, Long from, Long to) {
        return accountService.transfer(MY_ID,
                new TransferRequest(from, to, new BigDecimal(amount), "转账测试"));
    }

    // ==================== ★ 3.10 转账 ====================

    @Test
    void transferKeepsTotalUnchanged() {
        System.out.println("\n===== ★ 转账：一增一减，总额不变 =====");
        BigDecimal totalBefore = bal(accA).add(bal(accB));    // 1200

        String transferNo = transfer("100.00", accA, accB);

        System.out.printf("  A: 1000.00 → %s，B: 200.00 → %s%n", bal(accA), bal(accB));
        assertBal(accA, "900.00");
        assertBal(accB, "300.00");
        assertEquals(0, bal(accA).add(bal(accB)).compareTo(totalBefore),
                "★ 转账只是搬钱，两个账户的【总额】必须不变");
        assertNotNull(transferNo);
        System.out.printf("  总额 %s 未变；transferNo=%s（长度 %d）%n",
                totalBefore, transferNo, transferNo.length());
    }

    @Test
    void bothFlowsShareSameTransferNo() {
        System.out.println("\n===== ★ 两条流水共享 transfer_no =====");
        String transferNo = transfer("100.00", accA, accB);

        List<Map<String, Object>> flows = jdbcTemplate.queryForList(
                "SELECT account_id, direction, amount, flow_type, transfer_no, bill_id"
                        + " FROM t_account_flow WHERE transfer_no = ? AND deleted = 0 ORDER BY id", transferNo);

        System.out.println("  批次号 " + transferNo + "（长度 " + transferNo.length() + "）");
        flows.forEach(f -> System.out.println("    " + f));
        assertEquals(2, flows.size(), "★ 一次转账必须恰好 2 条流水");
        assertEquals(transferNo, flows.get(0).get("transfer_no"));
        assertEquals(transferNo, flows.get(1).get("transfer_no"));

        // 方向：转出 OUT、转入 IN（由 recordTransfer 内部固定）
        assertEquals("OUT", flows.get(0).get("direction"));
        assertEquals("IN", flows.get(1).get("direction"));
        assertEquals(accA, ((Number) flows.get(0).get("account_id")).longValue());
        assertEquals(accB, ((Number) flows.get(1).get("account_id")).longValue());

        // 转账不是消费，bill_id 必须是 null（不是 0）
        assertNull(flows.get(0).get("bill_id"), "★ 转账流水的 bill_id 应为 null");
        assertNull(flows.get(1).get("bill_id"));
        assertEquals("TRANSFER", flows.get(0).get("flow_type"));
        System.out.println("  ✓ 2 条流水共享批次号、方向正确、bill_id 为 null");

        assertReconciled("转账后");
        System.out.println("  ✓ 对账通过");
    }

    @Test
    void transferNoFitsColumnWidth() {
        System.out.println("\n===== 批次号长度与 varchar(32) 对齐 =====");
        String no = transfer("10.00", accA, accB);
        System.out.println("  transferNo = " + no + "（" + no.length() + " 字符）");
        assertEquals(32, no.length(), "★ UUID 去横线正好 32 位，与 transfer_no varchar(32) 严丝合缝");
        assertTrue(no.matches("[0-9a-f]{32}"), "应为无横线的十六进制 UUID");
        System.out.println("  ✓ 长度 32、无横线，不会截断");
    }

    @Test
    void twoTransfersGetDifferentTransferNo() {
        System.out.println("\n===== 两次转账的批次号必须不同 =====");
        String no1 = transfer("10.00", accA, accB);
        String no2 = transfer("20.00", accA, accB);
        System.out.println("  " + no1 + "\n  " + no2);
        assertNotEquals(no1, no2, "★ 撞号会导致撤销时一次删掉两批流水");
        System.out.println("  ✓ 批次号唯一");
    }

    @Test
    void sameAccountTransferIsRejected() {
        System.out.println("\n===== 转出转入相同应拒绝 =====");
        BusinessException e = assertThrows(BusinessException.class,
                () -> transfer("100.00", accA, accA));
        assertEquals(ErrorCode.ACCOUNT_SAME_TRANSFER.getCode(), e.getCode());
        System.out.println("  被拒(" + e.getCode() + ")：" + e.getMessage());
        assertBal(accA, "1000.00");
        assertEquals(0, activeFlowCount(accA), "不该留下任何流水");
        System.out.println("  ✓ 未产生任何流水（否则会留下两条互相抵消的垃圾数据）");
    }

    @Test
    void insufficientBalanceIsRejected() {
        System.out.println("\n===== 余额不足应拒绝 =====");
        BusinessException e = assertThrows(BusinessException.class,
                () -> transfer("9999.00", accA, accB));
        assertEquals(ErrorCode.ACCOUNT_NOT_ENOUGH_BALANCE.getCode(), e.getCode());
        System.out.println("  A 有 1000，转 9999 → 被拒(" + e.getCode() + ") ✓");
        assertBal(accA, "1000.00");
        assertBal(accB, "200.00");
        assertEquals(0, activeFlowCount(accA));

        // 恰好等于余额应允许（边界）
        transfer("1000.00", accA, accB);
        assertBal(accA, "0.00");
        assertBal(accB, "1200.00");
        System.out.println("  ✓ 恰好等于余额可转（1000 → A=0.00, B=1200.00）");
        assertReconciled("转空后");
    }

    @Test
    void cannotTransferOthersAccount() {
        System.out.println("\n===== 越权：不能转别人的账户 =====");
        Long otherAcc = newAccount(OTHER_ID, "别人的卡", "5000.00");

        BusinessException e1 = assertThrows(BusinessException.class,
                () -> transfer("100.00", otherAcc, accB));
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND.getCode(), e1.getCode());
        System.out.println("  用别人的账户转出 → 被拒(" + e1.getCode() + ") ✓");

        BusinessException e2 = assertThrows(BusinessException.class,
                () -> transfer("100.00", accA, otherAcc));
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND.getCode(), e2.getCode());
        System.out.println("  转入别人的账户 → 被拒(" + e2.getCode() + ") ✓");

        assertBal(accA, "1000.00");
        assertBal(otherAcc, "5000.00");
        System.out.println("  ✓ 双方余额均未变");
    }

    @Test
    void zeroOrNegativeAmountIsRejected() {
        System.out.println("\n===== 金额必须大于 0 =====");
        for (String bad : new String[]{"0.00", "-50.00"}) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> transfer(bad, accA, accB));
            System.out.println("  金额 " + bad + " → 被拒(" + e.getCode() + ")");
        }
        assertBal(accA, "1000.00");
        System.out.println("  ✓ 余额未变");
    }

    // ==================== ★ 3.11 撤销转账 ====================

    @Test
    void cancelTransferRestoresBothAccounts() {
        System.out.println("\n===== ★ 撤销转账：两账户都恢复原值 =====");
        String transferNo = transfer("100.00", accA, accB);
        assertBal(accA, "900.00");
        assertBal(accB, "300.00");

        flowService.removeByTransferNo(MY_ID, transferNo);

        System.out.printf("  撤销后 A=%s, B=%s%n", bal(accA), bal(accB));
        assertBal(accA, "1000.00");
        assertBal(accB, "200.00");
        assertEquals(0, activeFlowCount(accA), "★ 不能残留半条流水（A 侧）");
        assertEquals(0, activeFlowCount(accB), "★ 不能残留半条流水（B 侧）");
        assertReconciled("撤销后");
        System.out.println("  ✓ 两账户恢复、两条流水都作废、对账通过");
    }

    @Test
    void cancelNonexistentTransferIsRejected() {
        System.out.println("\n===== 撤销不存在的批次号 =====");
        BusinessException e = assertThrows(BusinessException.class,
                () -> flowService.removeByTransferNo(MY_ID, "00000000000000000000000000000000"));
        assertEquals(ErrorCode.ACCOUNT_TRANSFER_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  被拒(" + e.getCode() + ") ✓");

        BusinessException e2 = assertThrows(BusinessException.class,
                () -> flowService.removeByTransferNo(MY_ID, null));
        assertEquals(ErrorCode.ACCOUNT_TRANSFER_NOT_FOUND.getCode(), e2.getCode());
        System.out.println("  null → 被拒(" + e2.getCode() + ") ✓");
    }

    @Test
    void cannotCancelOthersTransfer() {
        System.out.println("\n===== 越权：不能撤销别人的转账 =====");
        Long otherA = newAccount(OTHER_ID, "别人的A", "1000.00");
        Long otherB = newAccount(OTHER_ID, "别人的B", "0.00");
        String otherNo = accountService.transfer(OTHER_ID,
                new TransferRequest(otherA, otherB, new BigDecimal("300.00"), "别人的转账"));
        assertBal(otherA, "700.00");

        BusinessException e = assertThrows(BusinessException.class,
                () -> flowService.removeByTransferNo(MY_ID, otherNo));
        assertEquals(ErrorCode.ACCOUNT_TRANSFER_NOT_FOUND.getCode(), e.getCode());

        System.out.printf("  用我的 userId 撤销别人的批次 → 被拒(%d)；对方余额仍为 %s%n",
                e.getCode(), bal(otherA));
        assertBal(otherA, "700.00");
        assertBal(otherB, "300.00");
        assertEquals(1, activeFlowCount(otherA), "★ 别人的流水不能被删");
        System.out.println("  ✓ 越权无效（user_id 条件让它查不到）");
    }

    @Test
    void cancelIsIdempotentInTheSenseOfBeingRejected() {
        System.out.println("\n===== 重复撤销 =====");
        String no = transfer("50.00", accA, accB);
        flowService.removeByTransferNo(MY_ID, no);
        assertBal(accA, "1000.00");

        BusinessException e = assertThrows(BusinessException.class,
                () -> flowService.removeByTransferNo(MY_ID, no));
        assertEquals(ErrorCode.ACCOUNT_TRANSFER_NOT_FOUND.getCode(), e.getCode());
        assertBal(accA, "1000.00");
        assertBal(accB, "200.00");
        System.out.println("  第二次被拒(" + e.getCode() + ")，余额未被重复回滚 ✓");
    }

    @Test
    void onlyAffectedBatchIsCancelled() {
        System.out.println("\n===== 只撤销指定的那一批 =====");
        String no1 = transfer("100.00", accA, accB);   // A 900, B 300
        String no2 = transfer("50.00", accA, accB);    // A 850, B 350
        assertBal(accA, "850.00");

        flowService.removeByTransferNo(MY_ID, no1);

        System.out.printf("  撤销第 1 批后 A=%s, B=%s（第 2 批应保留）%n", bal(accA), bal(accB));
        assertBal(accA, "950.00");   // 850 + 100
        assertBal(accB, "250.00");   // 350 - 100
        assertEquals(1, activeFlowCount(accA), "第 2 批的流水应仍在");
        assertReconciled("撤销一批后");
        System.out.println("  ✓ 只影响目标批次，另一批完好；对账通过");
    }

    // ==================== ★ 3.13 手动校正 ====================

    @Test
    void adjustToHigherBalanceWritesInFlow() {
        System.out.println("\n===== 校正：调高余额应记 IN 流水 =====");
        accountService.adjust(MY_ID, accA, new AdjustRequest(new BigDecimal("1500.00"), "实际余额更多"));

        assertBal(accA, "1500.00");
        Map<String, Object> f = jdbcTemplate.queryForMap(
                "SELECT direction, amount, flow_type FROM t_account_flow"
                        + " WHERE account_id = ? AND deleted = 0", accA);
        System.out.println("  余额 1000 → 1500；流水 " + f);
        assertEquals("IN", f.get("direction"));
        assertEquals(0, ((BigDecimal) f.get("amount")).compareTo(new BigDecimal("500.00")));
        assertEquals("ADJUST", f.get("flow_type"));
        assertReconciled("校正调高后");
        System.out.println("  ✓ 记 IN 500，对账通过（证明写了流水而不是直接改 balance）");
    }

    @Test
    void adjustToLowerBalanceWritesOutFlow() {
        System.out.println("\n===== 校正：调低余额应记 OUT 流水 =====");
        accountService.adjust(MY_ID, accA, new AdjustRequest(new BigDecimal("600.00"), "实际余额更少"));

        assertBal(accA, "600.00");
        Map<String, Object> f = jdbcTemplate.queryForMap(
                "SELECT direction, amount, flow_type FROM t_account_flow"
                        + " WHERE account_id = ? AND deleted = 0", accA);
        System.out.println("  余额 1000 → 600；流水 " + f);
        assertEquals("OUT", f.get("direction"));
        assertEquals(0, ((BigDecimal) f.get("amount")).compareTo(new BigDecimal("400.00")),
                "amount 恒为正数，方向由 direction 表达");
        assertReconciled("校正调低后");
        System.out.println("  ✓ 记 OUT 400（金额为正），对账通过");
    }

    @Test
    void adjustToSameBalanceWritesNothingAndDoesNotFail() {
        System.out.println("\n===== ★ 校正到相同余额：不报错、不写流水 =====");
        BigDecimal before = bal(accA);
        long flowsBefore = activeFlowCount(accA);

        // ★ 这条专治"硬写一条 amount=0 的流水"的写法 ——
        //   amount 的约定是恒为正数，写入路径会抛 4001
        accountService.adjust(MY_ID, accA, new AdjustRequest(new BigDecimal("1000.00"), "没改动"));

        System.out.printf("  余额 %s → %s；流水条数 %d → %d%n",
                before, bal(accA), flowsBefore, activeFlowCount(accA));
        assertEquals(0, bal(accA).compareTo(before));
        assertEquals(flowsBefore, activeFlowCount(accA),
                "★ 不该写一条金额为 0 的流水（违反 amount 恒为正的约定）");
        assertReconciled("无变化校正后");
        System.out.println("  ✓ 直接返回成功，未产生流水，对账通过");
    }

    @Test
    void adjustDoesNotChangeInitBalance() {
        System.out.println("\n===== 校正不改动 init_balance =====");
        BigDecimal initBefore = jdbcTemplate.queryForObject(
                "SELECT init_balance FROM t_account WHERE id = ?", BigDecimal.class, accA);

        accountService.adjust(MY_ID, accA, new AdjustRequest(new BigDecimal("8888.00"), "调高"));

        BigDecimal initAfter = jdbcTemplate.queryForObject(
                "SELECT init_balance FROM t_account WHERE id = ?", BigDecimal.class, accA);
        System.out.printf("  init_balance: %s → %s；balance: %s%n", initBefore, initAfter, bal(accA));
        assertEquals(0, initAfter.compareTo(initBefore), "★ 期初余额的定义是『建账户时填写，之后不变』");
        assertBal(accA, "8888.00");
        assertReconciled("校正后");
        System.out.println("  ✓ init_balance 未变，对账仍成立");
    }

    @Test
    void adjustAllowsNegativeTargetAndToZero() {
        System.out.println("\n===== 校正到 0 / 负数（透支是合法状态）=====");
        accountService.adjust(MY_ID, accA, new AdjustRequest(new BigDecimal("0.00"), "清零"));
        assertBal(accA, "0.00");
        assertReconciled("校正到 0 后");

        accountService.adjust(MY_ID, accA, new AdjustRequest(new BigDecimal("-300.00"), "记成欠款"));
        assertBal(accA, "-300.00");
        assertReconciled("校正到负数后");
        System.out.println("  0.00 与 -300.00 都能校正，对账始终通过 ✓");
    }

    @Test
    void cannotAdjustOthersAccount() {
        System.out.println("\n===== 越权：不能校正别人的账户 =====");
        Long otherAcc = newAccount(OTHER_ID, "别人的卡", "500.00");
        BusinessException e = assertThrows(BusinessException.class,
                () -> accountService.adjust(MY_ID, otherAcc, new AdjustRequest(new BigDecimal("1.00"), "试图改")));
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND.getCode(), e.getCode());
        assertBal(otherAcc, "500.00");
        System.out.println("  被拒(" + e.getCode() + ")，对方余额未变 ✓");
    }

    // ==================== ★ 3.12 流水列表 ====================

    @Test
    void flowListShowsAllThreeTypes() {
        System.out.println("\n===== ★ 三类来源都能正确展示 =====");

        // ① BILL：记账选了账户
        Long bookId = bookService.createDefaultBook(MY_ID).getId();
        jdbcTemplate.update(
                "INSERT INTO t_bill (user_id, book_id, category_id, type, amount, bill_time, source, deleted, create_time, update_time)"
                        + " VALUES (?, ?, 1001, 'EXPENSE', 25.00, NOW(), 'MANUAL', 0, NOW(), NOW())", MY_ID, bookId);
        Long billId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_bill WHERE user_id = ? ORDER BY id DESC LIMIT 1", Long.class, MY_ID);
        flowService.record(MY_ID, accA, AccountFlowService.FLOW_TYPE_BILL, billId,
                AccountFlowService.DIRECTION_OUT, new BigDecimal("25.00"),
                java.time.LocalDateTime.now(), "午餐");

        // ② TRANSFER
        String transferNo = transfer("100.00", accA, accB);

        // ③ ADJUST
        accountService.adjust(MY_ID, accA, new AdjustRequest(new BigDecimal("2000.00"), "校正"));

        List<AccountFlowVO> flows = accountService.listFlows(MY_ID, accA, null, 1, 20).getRecords();
        System.out.println("  A 账户的流水：");
        flows.forEach(f -> System.out.printf("    %s %s %s 对方=%s billId=%s%n",
                f.flowType(), f.direction(), f.amount(), f.counterpartAccountName(), f.billId()));

        assertEquals(3, flows.size(), "应有 BILL / TRANSFER / ADJUST 三条");
        assertEquals(3, flows.stream().map(AccountFlowVO::flowType).distinct().count(),
                "★ 三类来源都要出现");
        assertReconciled("三类流水齐备后");
        System.out.println("  ✓ BILL / TRANSFER / ADJUST 三类都在列表里");
    }

    @Test
    void transferFlowCarriesCounterpartAccountName() {
        System.out.println("\n===== ★ 转账流水带『对方账户名』=====");
        transfer("100.00", accA, accB);

        AccountFlowVO out = accountService.listFlows(MY_ID, accA, null, 1, 20).getRecords().get(0);
        AccountFlowVO in = accountService.listFlows(MY_ID, accB, null, 1, 20).getRecords().get(0);

        System.out.printf("  A 侧：%s %s，对方=%s%n", out.direction(), out.amount(), out.counterpartAccountName());
        System.out.printf("  B 侧：%s %s，对方=%s%n", in.direction(), in.amount(), in.counterpartAccountName());

        assertEquals("OUT", out.direction());
        assertEquals("微信零钱", out.counterpartAccountName(), "★ 否则『转出 100』看不懂转给谁了");
        assertEquals("IN", in.direction());
        assertEquals("招行卡", in.counterpartAccountName());
        assertNotNull(out.transferNo());
        System.out.println("  ✓ 两侧都能看到对方账户名");
    }

    @Test
    void nonTransferFlowHasNoCounterpart() {
        System.out.println("\n===== 非转账流水没有对方账户 =====");
        // 造一条 ADJUST
        accountService.adjust(MY_ID, accA, new AdjustRequest(new BigDecimal("1500.00"), "校正"));

        AccountFlowVO vo = accountService.listFlows(MY_ID, accA, null, 1, 20).getRecords().get(0);
        System.out.println("  ADJUST 流水：counterpart=" + vo.counterpartAccountName()
                + ", transferNo=" + vo.transferNo() + ", billId=" + vo.billId());
        assertNull(vo.counterpartAccountName(), "ADJUST 没有对方账户");
        assertNull(vo.transferNo());
        System.out.println("  ✓ 字段为 null，前端需按 flowType 决定展示");
    }

    @Test
    void flowListFiltersByType() {
        System.out.println("\n===== 按 flowType 筛选 =====");
        transfer("100.00", accA, accB);
        accountService.adjust(MY_ID, accA, new AdjustRequest(new BigDecimal("1500.00"), "校正"));

        long transferOnly = accountService.listFlows(MY_ID, accA, "TRANSFER", 1, 20).getTotal();
        long adjustOnly = accountService.listFlows(MY_ID, accA, "ADJUST", 1, 20).getTotal();
        long all = accountService.listFlows(MY_ID, accA, null, 1, 20).getTotal();

        System.out.printf("  全部=%d，TRANSFER=%d，ADJUST=%d%n", all, transferOnly, adjustOnly);
        assertEquals(1, transferOnly);
        assertEquals(1, adjustOnly);
        assertEquals(2, all);
        System.out.println("  ✓ 类型筛选正确");
    }

    @Test
    void flowListIsIsolatedByAccountAndUser() {
        System.out.println("\n===== 流水列表的账户/用户隔离 =====");
        transfer("100.00", accA, accB);

        assertEquals(1, accountService.listFlows(MY_ID, accA, null, 1, 20).getTotal(), "A 只有转出 1 条");
        assertEquals(1, accountService.listFlows(MY_ID, accB, null, 1, 20).getTotal(), "B 只有转入 1 条");

        // 别人的账户 → 3020（AccountService 层的归属校验）
        Long otherAcc = newAccount(OTHER_ID, "别人的卡", "500.00");
        BusinessException e = assertThrows(BusinessException.class,
                () -> accountService.listFlows(MY_ID, otherAcc, null, 1, 20));
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  查别人的账户流水 → 被拒(" + e.getCode() + ") ✓");
    }

    @Test
    void flowListPaginatesAndOrdersStably() {
        System.out.println("\n===== 流水列表分页与排序稳定 =====");
        for (int i = 1; i <= 6; i++) {
            transfer(i + ".00", accA, accB);
        }
        var p1 = accountService.listFlows(MY_ID, accA, null, 1, 3);
        var p2 = accountService.listFlows(MY_ID, accA, null, 2, 3);

        System.out.printf("  共 6 条；第1页 %d 条，第2页 %d 条，total=%d%n",
                p1.getRecords().size(), p2.getRecords().size(), p1.getTotal());
        assertEquals(3, p1.getRecords().size());
        assertEquals(3, p2.getRecords().size());
        assertEquals(6, p1.getTotal());

        var ids1 = p1.getRecords().stream().map(AccountFlowVO::id).toList();
        var ids2 = p2.getRecords().stream().map(AccountFlowVO::id).toList();
        assertTrue(ids1.stream().noneMatch(ids2::contains), "★ 相邻页不能重复（id 倒序 tie-breaker）");
        System.out.println("  ✓ 分页正确、无重叠");
    }

    // ==================== ACCOUNT_IN_USE ====================

    @Test
    void accountWithFlowsCannotBeDeleted() {
        System.out.println("\n===== ★ 有流水的账户不能删除 =====");
        transfer("100.00", accA, accB);

        BusinessException e = assertThrows(BusinessException.class,
                () -> accountService.delete(MY_ID, accA));
        assertEquals(ErrorCode.ACCOUNT_IN_USE.getCode(), e.getCode());
        System.out.println("  被拒(" + e.getCode() + ")：" + e.getMessage());

        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM t_account WHERE id = ?", Integer.class, accA);
        assertEquals(0, deleted, "账户不该被删");
        System.out.println("  ✓ 账户仍在");

        // 撤销转账后流水作废 → 就能删了
        flowService.removeByTransferNo(MY_ID, jdbcTemplate.queryForObject(
                "SELECT transfer_no FROM t_account_flow WHERE account_id = ? AND deleted = 0 LIMIT 1",
                String.class, accA));
        accountService.delete(MY_ID, accA);
        Integer after = jdbcTemplate.queryForObject(
                "SELECT deleted FROM t_account WHERE id = ?", Integer.class, accA);
        System.out.println("  撤销转账后再删 → deleted=" + after);
        assertEquals(1, after, "流水作废后应可删除");
        System.out.println("  ✓ 流水作废后可以删除（逻辑删除为 1）");
    }

    @Test
    void accountWithoutFlowsCanBeDeleted() {
        System.out.println("\n===== 没有流水的账户可以正常删除 =====");
        Long spare = newAccount(MY_ID, "备用卡", "0.00");
        accountService.delete(MY_ID, spare);
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM t_account WHERE id = ?", Integer.class, spare);
        assertEquals(1, deleted);
        System.out.println("  无流水的账户删除 → deleted=1 ✓");
    }

    // ==================== 综合 ====================

    @Test
    void reconcileStaysGreenThroughFullWorkflow() {
        System.out.println("\n===== 全流程对账 =====");
        assertReconciled("初始");

        String no1 = transfer("300.00", accA, accB);
        assertReconciled("转账 1");
        String no2 = transfer("50.00", accB, accA);
        assertReconciled("反向转账");
        accountService.adjust(MY_ID, accA, new AdjustRequest(new BigDecimal("1234.56"), "校正"));
        assertReconciled("校正");
        flowService.removeByTransferNo(MY_ID, no1);
        assertReconciled("撤销转账 1");
        flowService.removeByTransferNo(MY_ID, no2);
        assertReconciled("撤销转账 2");

        System.out.printf("  最终 A=%s, B=%s%n", bal(accA), bal(accB));

        // ★ 注意顺序：校正在【两次转账之后】执行，撤销又在校正之后，
        //   所以 A 会在校正值的基础上继续被撤销影响累加：
        //     校正后          A = 1234.56
        //     撤销 no1（A→B 300）→ A + 300
        //     撤销 no2（B→A 50） → A − 50
        //     净 +250        → A = 1484.56
        //   B 侧则被两次撤销完整抵消，回到初始的 200.00
        assertBal(accA, "1484.56");
        assertBal(accB, "200.00");
        System.out.println("  ✓ 五个步骤后对账始终通过；B 的转账效果被完整抵消，A 保留校正+撤销的净效果");
    }
}
