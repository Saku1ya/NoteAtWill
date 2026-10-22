package com.sakuya.notesatwill;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Account;
import com.sakuya.notesatwill.mapper.AccountMapper;
import com.sakuya.notesatwill.service.AccountFlowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code AccountFlowService.record} 的回归测试。
 *
 * <p>重点验证：
 * <ol>
 *   <li>IN / OUT 的方向换算（加还是减）—— 写反了余额会朝反方向跑，
 *       而且对账接口查不出来（流水和余额一致地错）</li>
 *   <li>流水与余额<b>同时</b>发生</li>
 *   <li>账户不合法时 —— 余额不动，且<b>流水也没被插入</b>
 *       （验证"先改余额、再写流水"的顺序优势）</li>
 *   <li>入参校验：负金额、非法方向都被拒</li>
 * </ol>
 *
 * @author sakuya
 */
@SpringBootTest
class AccountFlowRecordTest {

    private static final Long MY_ID = 987610001L;
    private static final Long OTHER_ID = 987610002L;

    @Autowired
    private AccountFlowService flowService;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long accountId;
    private Long otherAccountId;

    @BeforeEach
    void setUp() {
        clean();
        accountId = newAccount(MY_ID, "现金", "1000.00");
        otherAccountId = newAccount(OTHER_ID, "别人的现金", "500.00");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM t_account_flow WHERE user_id IN (?, ?)", MY_ID, OTHER_ID);
        jdbcTemplate.update("DELETE FROM t_account WHERE user_id IN (?, ?)", MY_ID, OTHER_ID);
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

    private BigDecimal balanceOf(Long id) {
        return jdbcTemplate.queryForObject("SELECT balance FROM t_account WHERE id = ?", BigDecimal.class, id);
    }

    private int flowCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_account_flow WHERE account_id = ?", Integer.class, accountId);
        return n == null ? 0 : n;
    }

    private int flowCount(Long accId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_account_flow WHERE account_id = ?", Integer.class, accId);
        return n == null ? 0 : n;
    }

    // ==================== 方向换算（核心）====================

    @Test
    void outDirectionDecreasesBalance() {
        System.out.println("\n===== OUT：支出应减少余额 =====");
        BigDecimal before = balanceOf(accountId);

        flowService.record(MY_ID, accountId, "BILL", 1L, "OUT",
                new BigDecimal("25.00"), LocalDateTime.now(), "午餐");

        BigDecimal after = balanceOf(accountId);
        System.out.printf("  余额 %s → %s（支出 25）%n", before, after);
        assertEquals(0, after.compareTo(new BigDecimal("975.00")), "★ OUT 应【减少】余额");
        assertEquals(1, flowCount());
        System.out.println("  ✓ OUT 正确扣减，并写入 1 条流水");
    }

    @Test
    void inDirectionIncreasesBalance() {
        System.out.println("\n===== IN：收入应增加余额 =====");
        BigDecimal before = balanceOf(accountId);

        flowService.record(MY_ID, accountId, "BILL", 2L, "IN",
                new BigDecimal("300.00"), LocalDateTime.now(), "工资");

        BigDecimal after = balanceOf(accountId);
        System.out.printf("  余额 %s → %s（收入 300）%n", before, after);
        assertEquals(0, after.compareTo(new BigDecimal("1300.00")), "★ IN 应【增加】余额");
        assertEquals(1, flowCount());
        System.out.println("  ✓ IN 正确增加，并写入 1 条流水");
    }

    @Test
    void directionsAreNotInverted() {
        System.out.println("\n===== 反向验证：IN 与 OUT 效果必须相反 =====");

        flowService.record(MY_ID, accountId, "BILL", 1L, "IN", new BigDecimal("100.00"), LocalDateTime.now(), null);
        BigDecimal afterIn = balanceOf(accountId);

        flowService.record(MY_ID, accountId, "BILL", 2L, "OUT", new BigDecimal("100.00"), LocalDateTime.now(), null);
        BigDecimal afterOut = balanceOf(accountId);

        System.out.printf("  +100 → %s，再 -100 → %s%n", afterIn, afterOut);
        assertEquals(0, afterIn.compareTo(new BigDecimal("1100.00")));
        assertEquals(0, afterOut.compareTo(new BigDecimal("1000.00")), "★ 一进一出应回到原值");
        System.out.println("  ✓ 方向未写反（回到初始 1000）");
    }

    // ==================== 流水与余额同时发生 ====================

    @Test
    void flowAndBalanceChangeTogether() {
        System.out.println("\n===== 一笔操作同时产生：1 条流水 + 余额变动 =====");

        flowService.record(MY_ID, accountId, "BILL", 99L, "OUT",
                new BigDecimal("0.01"), LocalDateTime.now(), "一分钱");

        Integer flows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_account_flow WHERE account_id = ? AND bill_id = 99", Integer.class, accountId);
        BigDecimal bal = balanceOf(accountId);
        System.out.printf("  流水条数=%d，余额=%s%n", flows, bal);
        assertEquals(1, flows);
        assertEquals(0, bal.compareTo(new BigDecimal("999.99")));
        System.out.println("  ✓ 两者一致（对账公式成立的必要条件）");
    }

    @Test
    void transferNoStaysNullForBillFlow() {
        System.out.println("\n===== BILL 流水的 transfer_no 应为 NULL =====");

        flowService.record(MY_ID, accountId, "BILL", 5L, "OUT",
                new BigDecimal("10.00"), LocalDateTime.now(), null);

        String transferNo = jdbcTemplate.queryForObject(
                "SELECT transfer_no FROM t_account_flow WHERE account_id = ? ORDER BY id DESC LIMIT 1",
                String.class, accountId);
        System.out.println("  transfer_no = " + transferNo);
        assertEquals(null, transferNo, "非转账流水不应有批次号");
        System.out.println("  ✓ 未 set 的字段由数据库默认值接管（不写进 SQL）");
    }

    @Test
    void deletedAndCreateTimeAreFilledByDatabase() {
        System.out.println("\n===== deleted / create_time 由数据库默认值填充 =====");

        flowService.record(MY_ID, accountId, "BILL", 6L, "OUT",
                new BigDecimal("1.00"), LocalDateTime.now(), null);

        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM t_account_flow WHERE account_id = ? ORDER BY id DESC LIMIT 1",
                Integer.class, accountId);
        String ct = jdbcTemplate.queryForObject(
                "SELECT create_time FROM t_account_flow WHERE account_id = ? ORDER BY id DESC LIMIT 1",
                String.class, accountId);
        System.out.println("  deleted = " + deleted + ", create_time = " + ct);
        assertEquals(0, deleted, "★ deleted 应由列默认值填 0（代码里不 set 它）");
        System.out.println("  ✓ 逻辑删除标记正常（若为 NULL，@TableLogic 的 deleted=0 过滤会失效）");
    }

    // ==================== 账户不合法 ====================

    @Test
    void invalidAccountDoesNotWriteFlowEither() {
        System.out.println("\n===== 账户不合法：余额不动，流水也不写 =====");

        int flowsBefore = flowCount();
        BigDecimal balBefore = balanceOf(accountId);

        // 用别人的 userId 配我的 accountId → addBalance 匹配不到
        BusinessException e = assertThrows(BusinessException.class,
                () -> flowService.record(OTHER_ID, accountId, "BILL", 7L, "OUT",
                        new BigDecimal("50.00"), LocalDateTime.now(), null),
                "账户不属于该用户时应抛异常");
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND.getCode(), e.getCode());

        System.out.printf("  异常 code=%d%n", e.getCode());
        assertEquals(0, balanceOf(accountId).compareTo(balBefore), "余额必须不变");
        assertEquals(flowsBefore, flowCount(), "★ 流水也不该被插入（先改余额再写流水的意义）");
        System.out.println("  ✓ 抛异常回滚，余额与流水都未改动");
    }

    @Test
    void nonexistentAccountIsRejected() {
        int flowsBefore = flowCount();

        BusinessException e = assertThrows(BusinessException.class,
                () -> flowService.record(MY_ID, 99999999L, "BILL", 8L, "OUT",
                        new BigDecimal("1.00"), LocalDateTime.now(), null));
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND.getCode(), e.getCode());
        assertEquals(flowsBefore, flowCount(), "不存在的账户不该产生流水");
        System.out.println("\n  不存在的账户被拒（" + e.getCode() + "），无流水产生 ✓");
    }

    @Test
    void deletedAccountIsRejected() {
        System.out.println("\n===== 已逻辑删除的账户 =====");
        jdbcTemplate.update("UPDATE t_account SET deleted = 1 WHERE id = ?", accountId);
        BigDecimal before = balanceOf(accountId);
        int flowsBefore = flowCount();

        BusinessException e = assertThrows(BusinessException.class,
                () -> flowService.record(MY_ID, accountId, "BILL", 9L, "OUT",
                        new BigDecimal("10.00"), LocalDateTime.now(), null));
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND.getCode(), e.getCode());
        assertEquals(0, balanceOf(accountId).compareTo(before));
        assertEquals(flowsBefore, flowCount());
        System.out.println("  已删除账户被拒，余额与流水均未变 ✓");
    }

    // ==================== 入参校验 ====================

    @Test
    void negativeAndZeroAmountAreRejected() {
        System.out.println("\n===== 金额必须为正 =====");

        for (String bad : new String[]{"-10.00", "0.00", "0"}) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> flowService.record(MY_ID, accountId, "BILL", 1L, "OUT",
                            new BigDecimal(bad), LocalDateTime.now(), null),
                    "金额 " + bad + " 应被拒");
            assertEquals(ErrorCode.BILL_AMOUNT_INVALID.getCode(), e.getCode());
            System.out.println("  金额 " + bad + " → 被拒(" + e.getCode() + ")");
        }

        BusinessException eNull = assertThrows(BusinessException.class,
                () -> flowService.record(MY_ID, accountId, "BILL", 1L, "OUT", null, LocalDateTime.now(), null));
        assertEquals(ErrorCode.BILL_AMOUNT_INVALID.getCode(), eNull.getCode());
        System.out.println("  金额 null → 被拒");
        System.out.println("  ✓ 负/零/null 全部拒绝（amount 恒为正数）");
    }

    @Test
    void invalidDirectionIsRejected() {
        System.out.println("\n===== 方向必须是 IN / OUT =====");

        for (String bad : new String[]{"in", "out", "INCOME", "", "XX"}) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> flowService.record(MY_ID, accountId, "BILL", 1L, bad,
                            new BigDecimal("10.00"), LocalDateTime.now(), null),
                    "方向 " + bad + " 应被拒");
            System.out.println("  方向 \"" + bad + "\" → 被拒");
        }
        // 大小写敏感：小写 "in" 不被接受，避免 IN/in 两种写法混用导致统计口径分裂
        System.out.println("  ✓ 非法方向全部拒绝（大小写敏感，避免 IN/in 混用）");
    }

    // ==================== 余额可为负 ====================

    @Test
    void overdraftIsAllowedForBillFlow() {
        System.out.println("\n===== 记账场景允许透支（余额转负）=====");

        flowService.record(MY_ID, accountId, "BILL", 1L, "OUT",
                new BigDecimal("1500.00"), LocalDateTime.now(), "大额支出");

        BigDecimal after = balanceOf(accountId);
        System.out.println("  1000 支出 1500 → " + after);
        assertEquals(0, after.compareTo(new BigDecimal("-500.00")),
                "记账不拦截透支（信用卡/先记后补是真实需求）");
        System.out.println("  ✓ 允许负余额；「余额不足」留给 D4 的转账场景判断");
    }
}
