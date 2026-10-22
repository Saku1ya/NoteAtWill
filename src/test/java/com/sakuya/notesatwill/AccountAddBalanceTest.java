package com.sakuya.notesatwill;

import com.sakuya.notesatwill.entity.Account;
import com.sakuya.notesatwill.mapper.AccountMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code AccountMapper.addBalance} 的回归测试。
 *
 * <p>验证四件事：
 * <ol>
 *   <li>加减法正确（含小数、负数 delta）</li>
 *   <li>越权防护：别的 userId 改不动</li>
 *   <li>已逻辑删除的账户改不动</li>
 *   <li><b>并发下不丢更新</b> —— 这是本方法存在的根本理由，
 *       也是"先查后算再写"会失败、而"数据库端自增"能通过的那个场景</li>
 * </ol>
 *
 * @author sakuya
 */
@SpringBootTest
class AccountAddBalanceTest {

    private static final Long MY_ID = 987620001L;
    private static final Long OTHER_ID = 987620002L;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long accountId;

    @BeforeEach
    void setUp() {
        clean();
        Account a = new Account();
        a.setUserId(MY_ID);
        a.setName("测试账户");
        a.setType("CASH");
        a.setInitBalance(new BigDecimal("1000.00"));
        a.setBalance(new BigDecimal("1000.00"));
        accountMapper.insert(a);
        accountId = a.getId();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM t_account WHERE user_id IN (?, ?)", MY_ID, OTHER_ID);
    }

    private BigDecimal balanceOf(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM t_account WHERE id = ?", BigDecimal.class, id);
    }

    // ==================== 基本加减 ====================

    @Test
    void addsAndSubtractsCorrectly() {
        System.out.println("\n===== addBalance 基本加减 =====");

        int r1 = accountMapper.addBalance(MY_ID, accountId, new BigDecimal("25.50"));
        assertEquals(1, r1, "应影响 1 行");
        System.out.println("  +25.50 → " + balanceOf(accountId));
        assertEquals(0, balanceOf(accountId).compareTo(new BigDecimal("1025.50")));

        int r2 = accountMapper.addBalance(MY_ID, accountId, new BigDecimal("-100.00"));
        assertEquals(1, r2);
        System.out.println("  -100.00 → " + balanceOf(accountId));
        assertEquals(0, balanceOf(accountId).compareTo(new BigDecimal("925.50")),
                "★ 用 compareTo 而不是 equals：1.0 与 1.00 的 equals 是 false");

        // 精度：0.1 + 0.2 在 double 下是 0.30000000000000004，
        // BigDecimal 应精确等于 0.30
        accountMapper.addBalance(MY_ID, accountId, new BigDecimal("0.10"));
        accountMapper.addBalance(MY_ID, accountId, new BigDecimal("0.20"));
        BigDecimal after = balanceOf(accountId);
        System.out.println("  +0.10 +0.20 → " + after);
        assertEquals(0, after.compareTo(new BigDecimal("925.80")), "★ BigDecimal 无浮点误差");
        System.out.println("  ✓ 加减与精度均正确");
    }

    @Test
    void deltaOfZeroKeepsBalance() {
        BigDecimal before = balanceOf(accountId);
        int r = accountMapper.addBalance(MY_ID, accountId, BigDecimal.ZERO);
        assertEquals(1, r, "delta=0 也应匹配到行");
        assertEquals(0, balanceOf(accountId).compareTo(before));
        System.out.println("\n  delta=0 → 余额不变，行仍被匹配 ✓");
    }

    // ==================== 越权与逻辑删除 ====================

    @Test
    void otherUserCannotChangeBalance() {
        System.out.println("\n===== 越权防护 =====");

        BigDecimal before = balanceOf(accountId);
        int r = accountMapper.addBalance(OTHER_ID, accountId, new BigDecimal("9999.00"));

        assertEquals(0, r, "别人的 userId 应改不动，返回 0");
        assertEquals(0, balanceOf(accountId).compareTo(before), "余额必须不变");
        System.out.println("  OTHER_ID 尝试 +9999 → 影响 0 行，余额仍为 " + before + " ✓");
    }

    @Test
    void deletedAccountCannotBeChanged() {
        System.out.println("\n===== 已逻辑删除的账户 =====");

        // 手工置 deleted=1（模拟账户被逻辑删除）
        jdbcTemplate.update("UPDATE t_account SET deleted = 1 WHERE id = ?", accountId);
        BigDecimal before = balanceOf(accountId);

        int r = accountMapper.addBalance(MY_ID, accountId, new BigDecimal("500.00"));
        assertEquals(0, r, "★ 已删除的账户不该被改 —— 证明手写 deleted=0 条件生效");
        assertEquals(0, balanceOf(accountId).compareTo(before));
        System.out.println("  已删除账户 → 影响 0 行，余额不变 ✓");
        System.out.println("  （@TableLogic 对手写 @Update 不生效，所以 SQL 里必须自己写 deleted = 0）");
    }

    @Test
    void nonexistentAccountReturnsZero() {
        int r = accountMapper.addBalance(MY_ID, 99999999L, new BigDecimal("1.00"));
        assertEquals(0, r, "账户不存在应返回 0，调用方据此抛异常回滚");
        System.out.println("\n  不存在的账户 → 返回 0 ✓（调用方必须检查并回滚）");
    }

    // ==================== 并发（核心）====================

    @Test
    void concurrentAddsDoNotLoseUpdates() throws Exception {
        System.out.println("\n===== 并发不丢更新（本方法存在的根本理由）=====");

        final int threads = 20;
        final BigDecimal each = new BigDecimal("10.00");
        BigDecimal start = balanceOf(accountId);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger affected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();   // 让所有线程尽量同时发起，制造真实争用
                    int r = accountMapper.addBalance(MY_ID, accountId, each);
                    if (r == 1) {
                        affected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        go.countDown();          // 发令
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        BigDecimal expected = start.add(each.multiply(BigDecimal.valueOf(threads)));
        BigDecimal actual = balanceOf(accountId);

        System.out.printf("  起始 %s，%d 个线程各 +%s%n", start, threads, each);
        System.out.printf("  期望 %s，实际 %s，成功行数 %d/%d%n",
                expected, actual, affected.get(), threads);

        assertEquals(0, actual.compareTo(expected),
                "★ 并发丢更新！实际 " + actual + " != 期望 " + expected
                        + " —— 说明没用数据库端自增");
        assertEquals(threads, affected.get(), "每次调用都应成功影响 1 行");
        System.out.println("  ✓ 20 并发各 +10，余额精确增加 200，无丢更新");
    }
}
