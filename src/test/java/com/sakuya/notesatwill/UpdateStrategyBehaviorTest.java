package com.sakuya.notesatwill;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sakuya.notesatwill.entity.Bill;
import com.sakuya.notesatwill.mapper.BillMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 实测 MyBatis-Plus {@code update-strategy: not_null} 的三种行为，
 * 直接决定 D3「修改账单」的实现方式。
 *
 * <p><b>本项目 {@code application.yml} 里 update-strategy = not_null</b>，
 * 于是"把某个字段改成 null"会踩三个坑，实测结论：
 *
 * <ol>
 *   <li><b>null 字段被跳过</b> —— 只 set 了 null 的字段不会出现在 SET 子句里。
 *       所以"用户清空备注"会<b>静默失败</b>（接口成功、库里没变）。</li>
 *   <li><b>★ 若除 id 外全部字段都是 null，生成的 SQL 是
 *       {@code UPDATE t_bill SET  WHERE id=? AND deleted=0}</b> ——
 *       SET 子句为空，<b>直接是语法错误</b>，抛异常而不是静默通过。</li>
 *   <li><b>空字符串会被正常写入</b>（"" 不是 null），可作为"清空"的替代做法；
 *       但真正语义正确的做法是用 {@link LambdaUpdateWrapper} 的 {@code set()} 强制写入 null。</li>
 * </ol>
 *
 * <p><b>对 D3 的结论</b>：{@code updateBill} 不要用"构建稀疏 Bill 实体 + updateById"，
 * 而应用 {@code LambdaUpdateWrapper} <b>显式 set 每一个可变字段</b>。
 * 那样既不依赖 update-strategy 配置（改配置也不会坏），
 * 又能正确写入 null。
 *
 * @author sakuya
 */
@SpringBootTest
class UpdateStrategyBehaviorTest {

    private static final Long TEST_USER = 987560001L;

    @Autowired
    private BillMapper billMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long billId;

    @BeforeEach
    void setUp() {
        tearDown();
        Bill b = new Bill();
        b.setUserId(TEST_USER);
        b.setBookId(1L);
        b.setCategoryId(1001L);
        b.setType("EXPENSE");
        b.setAmount(new BigDecimal("25.00"));
        b.setRemark("原备注");
        b.setBillTime(LocalDateTime.now());
        b.setSource("MANUAL");
        billMapper.insert(b);
        billId = b.getId();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM t_bill WHERE user_id = ?", TEST_USER);
    }

    private Map<String, Object> row() {
        return jdbcTemplate.queryForMap(
                "SELECT remark, amount FROM t_bill WHERE id = ?", billId);
    }

    // ==================== 坑 ①：null 被跳过 ====================

    @Test
    void nullFieldIsSkippedSoClearingRemarkSilentlyFails() {
        System.out.println("\n===== 坑①：只把 remark 设为 null → 被跳过 =====");
        System.out.println("  更新前 remark = \"" + row().get("remark") + "\"");

        Bill patch = new Bill();
        patch.setId(billId);
        patch.setRemark(null);          // 想清空备注
        patch.setAmount(new BigDecimal("30.00"));  // 同时改点别的，避免 SET 变空
        billMapper.updateById(patch);

        Object after = row().get("remark");
        System.out.println("  SQL 为: UPDATE t_bill SET amount=? WHERE id=? AND deleted=0");
        System.out.println("  更新后 remark = " + (after == null ? "null" : "\"" + after + "\""));
        assertNotNull(after, "★ null 被跳过：remark 没有被清空");
        assertEquals(0, ((BigDecimal) row().get("amount")).compareTo(new BigDecimal("30.00")),
                "非 null 的 amount 正常更新");
        System.out.println("  ✗ 结论：想清空备注而传 null，会【静默失败】—— 接口成功但库里没变");
    }

    // ==================== 坑 ②：SET 变空 → 语法错误 ====================

    @Test
    void allNullFieldsProduceInvalidSql() {
        System.out.println("\n===== 坑②：除 id 外全为 null → SQL 语法错误 =====");

        Bill patch = new Bill();
        patch.setId(billId);
        patch.setRemark(null);          // 唯一想改的字段是 null

        DataAccessException e = assertThrows(DataAccessException.class,
                () -> billMapper.updateById(patch),
                "★ 生成的 SQL 是 `UPDATE t_bill SET  WHERE ...`，SET 子句为空");

        System.out.println("  抛出异常: " + e.getClass().getSimpleName());
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        System.out.println("  原因: " + msg);
        System.out.println("  ★ 注意：这不是静默失败，而是【直接报错】——");
        System.out.println("     因为 update-strategy=not_null 把所有 null 字段都从 SET 子句剔除了");
        assertEquals("原备注", row().get("remark"), "报错后数据未变");
    }

    // ==================== 正确做法 ====================

    @Test
    void emptyStringIsWritten() {
        System.out.println("\n===== 替代做法A：用空字符串代替 null =====");

        Bill patch = new Bill();
        patch.setId(billId);
        patch.setRemark("");
        billMapper.updateById(patch);

        Object after = row().get("remark");
        System.out.println("  设为 \"\" → 更新后 remark = " + (after == null ? "null" : "\"" + after + "\""));
        assertEquals("", after, "空串不是 null，会被正常写入");
        System.out.println("  ✓ 可行，但库里存的是 \"\" 而不是 NULL —— 语义上与「没有备注」有细微差别");
    }

    @Test
    void updateWrapperCanWriteNull() {
        System.out.println("\n===== ★ 推荐做法：LambdaUpdateWrapper 显式 set =====");

        int affected = billMapper.update(null, new LambdaUpdateWrapper<Bill>()
                .eq(Bill::getId, billId)
                .eq(Bill::getUserId, TEST_USER)
                .set(Bill::getRemark, null)                 // 显式写入 null
                .set(Bill::getAmount, new BigDecimal("30.00")));

        Object after = row().get("remark");
        System.out.println("  affected=" + affected + "，更新后 remark = " + (after == null ? "null" : "\"" + after + "\""));
        assertNull(after, "★ UpdateWrapper 的 set 能强制写入 null，真正清空备注");
        assertEquals(0, ((BigDecimal) row().get("amount")).compareTo(new BigDecimal("30.00")));
        System.out.println("  ✓ 备注被真正清空（存 NULL），且 amount 同时更新");
        System.out.println("  ✓ 且不依赖 update-strategy 配置 —— 即使以后改成 not_empty 也不会坏");
    }

    @Test
    void updateWrapperStillRespectsLogicDelete() {
        System.out.println("\n===== UpdateWrapper 是否仍受 @TableLogic 保护 =====");

        jdbcTemplate.update("UPDATE t_bill SET deleted = 1 WHERE id = ?", billId);
        Object before = jdbcTemplate.queryForObject(
                "SELECT remark FROM t_bill WHERE id = ?", Object.class, billId);

        int affected = billMapper.update(null, new LambdaUpdateWrapper<Bill>()
                .eq(Bill::getId, billId)
                .set(Bill::getRemark, "试图改动已删除的行"));

        Object after = jdbcTemplate.queryForObject(
                "SELECT remark FROM t_bill WHERE id = ?", Object.class, billId);
        System.out.println("  已逻辑删除的行 → affected=" + affected + "，remark 仍为 " + before);
        assertEquals(0, affected, "★ 已删除的行不该被更新");
        assertEquals(before, after, "数据未变");
        System.out.println("  ✓ @TableLogic 自动追加 deleted=0，已删除行改不动");
    }

    @Test
    void partialUpdateDoesNotDisturbOtherFields() {
        System.out.println("\n===== updateById 部分更新不动其它字段 =====");

        BigDecimal before = (BigDecimal) row().get("amount");
        Bill patch = new Bill();
        patch.setId(billId);
        patch.setRemark("改过的备注");
        billMapper.updateById(patch);

        System.out.println("  amount: " + before + " → " + row().get("amount"));
        assertEquals(0, ((BigDecimal) row().get("amount")).compareTo(before),
                "未 set 的字段不会被覆盖成 null");
        assertEquals("改过的备注", row().get("remark"));
        System.out.println("  ✓ 只更新 remark，amount 未受影响（这正是 not_null 策略的价值）");
    }
}
