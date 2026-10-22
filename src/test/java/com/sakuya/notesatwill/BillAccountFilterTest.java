package com.sakuya.notesatwill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sakuya.notesatwill.entity.Bill;
import com.sakuya.notesatwill.mapper.BillMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实测「按账户筛选账单」的正确写法。
 *
 * <p><b>为什么必须实测</b>：{@code t_bill} 里没有 {@code account_id}，
 * 这个筛选条件必须跨到 {@code t_account_flow}，而手册给的验收标准是
 * <b>「没选账户的账单不会被误选进来」</b>。几种写法看起来都能跑，
 * 但在"分页"和"账单没有流水"这两个场景下行为不同。
 *
 * <p>造三条账单：
 * <pre>
 *   billA  → 有流水指向 accountA
 *   billB  → 有流水指向 accountB
 *   billC  → 【没有流水】（用户记账时未选账户）
 * </pre>
 * 然后用 accountA 筛选，正确结果应<b>只有 billA</b>。
 *
 * @author sakuya
 */
@SpringBootTest
class BillAccountFilterTest {

    private static final Long TEST_USER = 987580001L;
    private static final Long ACC_A = 888880001L;
    private static final Long ACC_B = 888880002L;

    @Autowired
    private BillMapper billMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long billA;
    private Long billB;
    private Long billC;

    @BeforeEach
    void setUp() {
        clean();
        billA = insertBill("用账户A支付");
        billB = insertBill("用账户B支付");
        billC = insertBill("未选账户");
        insertFlow(billA, ACC_A);
        insertFlow(billB, ACC_B);
        // billC 刻意不插流水
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM t_account_flow WHERE user_id = ?", TEST_USER);
        jdbcTemplate.update("DELETE FROM t_bill WHERE user_id = ?", TEST_USER);
    }

    private Long insertBill(String remark) {
        jdbcTemplate.update(
                "INSERT INTO t_bill (user_id, book_id, category_id, type, amount, remark, bill_time, source, deleted, create_time, update_time)"
                        + " VALUES (?, 1, 1001, 'EXPENSE', 10.00, ?, ?, 'MANUAL', 0, NOW(), NOW())",
                TEST_USER, remark, LocalDateTime.now());
        return jdbcTemplate.queryForObject(
                "SELECT id FROM t_bill WHERE user_id = ? AND remark = ? ORDER BY id DESC LIMIT 1",
                Long.class, TEST_USER, remark);
    }

    private void insertFlow(Long billId, Long accountId) {
        jdbcTemplate.update(
                "INSERT INTO t_account_flow (user_id, account_id, flow_type, bill_id, direction, amount, flow_time, deleted, create_time)"
                        + " VALUES (?, ?, 'BILL', ?, 'OUT', 10.00, NOW(), 0, NOW())",
                TEST_USER, accountId, billId);
    }

    private Set<Long> queryIds(LambdaQueryWrapper<Bill> wrapper) {
        return billMapper.selectList(wrapper).stream().map(Bill::getId).collect(Collectors.toSet());
    }

    // ==================== 方案对比 ====================

    @Test
    void inSqlSubquerySelectsOnlyMatchingBills() {
        System.out.println("\n===== 方案A：IN (子查询) =====");

        Set<Long> ids = queryIds(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getUserId, TEST_USER)
                .inSql(Bill::getId,
                        "SELECT bill_id FROM t_account_flow"
                                + " WHERE account_id = " + ACC_A
                                + " AND flow_type = 'BILL' AND deleted = 0")
        );

        System.out.println("  用 ACC_A 筛选 → 命中 " + ids.size() + " 条");
        assertTrue(ids.contains(billA), "★ 应包含 billA（它用了 ACC_A）");
        assertFalse(ids.contains(billB), "不应包含 billB（它用的是 ACC_B）");
        assertFalse(ids.contains(billC), "★★ 绝不能包含 billC —— 它没选账户，不该被误选");
        assertEquals(1, ids.size(), "应恰好 1 条");
        System.out.println("  ✓ 只命中 billA；未选账户的 billC 未被误选");
    }

    @Test
    void existsSubqueryIsParameterizedAndSafe() {
        System.out.println("\n===== 方案B：EXISTS（带占位符，可传参）=====");

        Set<Long> ids = queryIds(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getUserId, TEST_USER)
                .exists("SELECT 1 FROM t_account_flow f WHERE f.bill_id = t_bill.id"
                                + " AND f.account_id = {0} AND f.flow_type = 'BILL' AND f.deleted = 0",
                        ACC_A)
        );

        System.out.println("  用 ACC_A 筛选 → 命中 " + ids.size() + " 条");
        assertTrue(ids.contains(billA));
        assertFalse(ids.contains(billC), "★ 未选账户的账单不应被误选");
        assertEquals(1, ids.size());
        System.out.println("  ✓ EXISTS + {0} 占位符可用（值为数字型，无注入风险）");
    }

    @Test
    void bothApproachesAgree() {
        System.out.println("\n===== 两种方案结果应一致 =====");

        Set<Long> a = queryIds(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getUserId, TEST_USER)
                .inSql(Bill::getId, "SELECT bill_id FROM t_account_flow WHERE account_id = " + ACC_A
                        + " AND flow_type = 'BILL' AND deleted = 0"));

        Set<Long> b = queryIds(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getUserId, TEST_USER)
                .exists("SELECT 1 FROM t_account_flow f WHERE f.bill_id = t_bill.id AND f.account_id = {0}"
                        + " AND f.flow_type = 'BILL' AND f.deleted = 0", ACC_A));

        System.out.println("  IN 子查询   → " + a);
        System.out.println("  EXISTS      → " + b);
        assertEquals(a, b, "两种写法结果必须一致");
        System.out.println("  ✓ 结果一致");
    }

    // ==================== 关键：分页下的行为 ====================

    @Test
    void subqueryKeepsPaginationExact() {
        System.out.println("\n===== 分页正确性（子查询 vs JOIN 的差别）=====");

        // 再给 billA 插一条流水，模拟"一笔账单有多条流水"的异常数据。
        // 这不是正常情况（Service 层保证一笔账单最多一条 BILL 流水），
        // 但数据库层面没有唯一索引约束，所以要验证查询是否健壮。
        insertFlow(billA, ACC_A);

        Page<Bill> page = billMapper.selectPage(
                Page.of(1, 10),
                new LambdaQueryWrapper<Bill>()
                        .eq(Bill::getUserId, TEST_USER)
                        .inSql(Bill::getId, "SELECT bill_id FROM t_account_flow WHERE account_id = " + ACC_A
                                + " AND flow_type = 'BILL' AND deleted = 0")
                        .orderByDesc(Bill::getBillTime)
                        .orderByDesc(Bill::getId)
        );

        System.out.println("  billA 有 2 条流水，子查询筛选后 records=" + page.getRecords().size()
                + ", total=" + page.getTotal());
        // 子查询不会产生重复行：billA 只出现一次
        assertEquals(1, page.getRecords().size(), "★ 子查询不产生重复行（JOIN 会）");
        assertEquals(1, page.getTotal(), "★ total 也精确（JOIN 会把 total 算成 2）");
        System.out.println("  ✓ 子查询方案下分页精确：账单出现 1 次、total=1");
        System.out.println("  （若用 JOIN，同一账单会出现 2 行、total 虚高 —— 这就是选子查询的原因）");
    }

    @Test
    void noFlowBillsAreExcludedByDesign() {
        System.out.println("\n===== 不按账户筛选时，未选账户的账单应正常出现 =====");

        Set<Long> all = queryIds(new LambdaQueryWrapper<Bill>().eq(Bill::getUserId, TEST_USER));
        System.out.println("  不加账户条件 → " + all.size() + " 条");
        assertEquals(3, all.size(), "三条账单都应返回（含未选账户的 billC）");
        assertTrue(all.contains(billC), "未选账户的账单在不筛账户时必须能查到");
        System.out.println("  ✓ 未选账户的账单只在【不筛账户】时出现，逻辑正确");
    }

    @Test
    void filterByTwoAccountsReturnsBoth() {
        System.out.println("\n===== 按多个账户筛选（IN 列表）=====");

        Set<Long> ids = queryIds(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getUserId, TEST_USER)
                .inSql(Bill::getId, "SELECT bill_id FROM t_account_flow WHERE account_id IN ("
                        + ACC_A + "," + ACC_B + ") AND flow_type = 'BILL' AND deleted = 0"));

        System.out.println("  ACC_A + ACC_B → " + ids.size() + " 条");
        assertEquals(2, ids.size());
        assertTrue(ids.contains(billA) && ids.contains(billB));
        assertFalse(ids.contains(billC), "未选账户的仍不该出现");
        System.out.println("  ✓ 多账户筛选正确，未选账户的仍未混入");
    }

    @Test
    void deletedFlowDoesNotMatch() {
        System.out.println("\n===== 已逻辑删除的流水不参与筛选 =====");

        jdbcTemplate.update("UPDATE t_account_flow SET deleted = 1 WHERE bill_id = ?", billA);

        Set<Long> ids = queryIds(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getUserId, TEST_USER)
                .inSql(Bill::getId, "SELECT bill_id FROM t_account_flow WHERE account_id = " + ACC_A
                        + " AND flow_type = 'BILL' AND deleted = 0"));

        System.out.println("  billA 的流水被逻辑删除后 → 命中 " + ids.size() + " 条");
        assertEquals(0, ids.size(), "★ 子查询里的 deleted = 0 必须手写（@TableLogic 不作用于手写 SQL）");
        System.out.println("  ✓ 已删除流水不匹配（证明子查询里手写的 deleted=0 生效）");
    }

    @Test
    void onlyBillTypeFlowsCount() {
        System.out.println("\n===== 只认 flow_type = 'BILL' 的流水 =====");

        // 造一条 ADJUST 流水，也挂上 bill_id（现实中不会这样，用于验证过滤条件有效）
        jdbcTemplate.update(
                "INSERT INTO t_account_flow (user_id, account_id, flow_type, bill_id, direction, amount, flow_time, deleted, create_time)"
                        + " VALUES (?, ?, 'ADJUST', ?, 'OUT', 5.00, NOW(), 0, NOW())",
                TEST_USER, ACC_A, billC);

        Set<Long> ids = queryIds(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getUserId, TEST_USER)
                .inSql(Bill::getId, "SELECT bill_id FROM t_account_flow WHERE account_id = " + ACC_A
                        + " AND flow_type = 'BILL' AND deleted = 0"));

        System.out.println("  billC 挂了 ADJUST 流水 → 命中 " + ids.size() + " 条");
        assertFalse(ids.contains(billC), "★ 非 BILL 类型的流水不该让账单被选出");
        System.out.println("  ✓ flow_type='BILL' 过滤生效");
    }

    @Test
    void sqlSegmentsLookRight() {
        System.out.println("\n===== 生成的 SQL 片段（人工核对）=====");

        LambdaQueryWrapper<Bill> w = new LambdaQueryWrapper<Bill>()
                .eq(Bill::getUserId, TEST_USER)
                .inSql(Bill::getId, "SELECT bill_id FROM t_account_flow WHERE account_id = " + ACC_A)
                .orderByDesc(Bill::getBillTime)
                .orderByDesc(Bill::getId);

        System.out.println("  [SQL] " + w.getSqlSegment());
        List<Bill> list = billMapper.selectList(w);
        System.out.println("  实际执行返回 " + list.size() + " 条");
        System.out.println("  （控制台同时会打印 MyBatis 的完整 SQL，可对照确认无重复行）");
    }
}
