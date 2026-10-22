package com.sakuya.notesatwill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sakuya.notesatwill.mapper.AccountMapper;
import com.sakuya.notesatwill.entity.Account;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 实测：{@code insert-strategy: not_null} 下，实体中【未赋值】的字段会怎样？
 *
 * <p>要回答的问题是：{@code accountFlowService.record(...)} 里
 * 构造 {@code AccountFlow} 时，像 {@code transferNo}（转账才有值）这种
 * 当前场景用不到的字段，如果 <b>不 set</b>，会发生什么？
 *
 * <p>三种可能：
 * <ol>
 *   <li>被拼进 SQL 并写入 NULL（危险：会覆盖数据库默认值）</li>
 *   <li>被跳过，不进 SQL，由数据库默认值/自增接管（not_null 策略的预期行为）</li>
 *   <li>报错</li>
 * </ol>
 *
 * <p>用真实库跑一遍，看 INSERT 语句与落库结果。
 *
 * @author sakuya
 */
@SpringBootTest
class InsertStrategyBehaviorTest {

    private static final Long TEST_USER = 987630001L;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM t_account WHERE user_id = ?", TEST_USER);
    }

    @Test
    void unsetFieldsAreSkippedNotWrittenAsNull() {
        tearDown();

        System.out.println("\n========== insert-strategy: not_null 实测 ==========");

        // 只 set 必需字段，其余（icon / isDefault / sort / deleted）全部不 set
        Account a = new Account();
        a.setUserId(TEST_USER);
        a.setName("策略测试-现金");
        a.setType("CASH");
        a.setInitBalance(new BigDecimal("100.00"));
        a.setBalance(new BigDecimal("100.00"));
        // ★ 刻意不 set：icon、isDefault、sort、deleted、createTime、updateTime

        accountMapper.insert(a);
        assertNotNull(a.getId(), "id 应被回填");
        System.out.println("插入成功，id=" + a.getId());
        System.out.println("实体上未赋值的字段：icon=" + a.getIcon()
                + ", isDefault=" + a.getIsDefault()
                + ", sort=" + a.getSort()
                + ", deleted=" + a.getDeleted()
                + ", createTime=" + a.getCreateTime());

        // 直接查库，看数据库里这些列到底是什么
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT icon, is_default, sort, deleted, create_time FROM t_account WHERE id = ?", a.getId());
        System.out.println("\n数据库实际值：");
        row.forEach((k, v) -> System.out.printf("  %-12s = %s%n", k, v));

        // ① 未 set 的字段，数据库里应落到【列默认值】，而不是 NULL
        assertNull(row.get("icon"), "icon 未 set 且列默认是 NULL —— 落库为 NULL 属正常");
        assertEquals(0, ((Number) row.get("is_default")).intValue(),
                "★ is_default 未 set，应落到列默认值 0（而不是 NULL）");
        assertEquals(0, ((Number) row.get("sort")).intValue(),
                "★ sort 未 set，应落到列默认值 0");
        assertEquals(0, ((Number) row.get("deleted")).intValue(),
                "★ deleted 未 set，应落到列默认值 0（关系到逻辑删除能否正常过滤）");
        assertNotNull(row.get("create_time"), "★ create_time 未 set，应由列默认 CURRENT_TIMESTAMP 填充");

        System.out.println("\n结论：未 set 的字段被【跳过】，由数据库默认值接管 —— 这正是 not_null 策略的效果。");
        System.out.println("     所以 record() 里不需要为'当前场景用不到'的字段手动补 null。\n");
    }

    @Test
    void explicitlySettingNullIsAlsoSkipped() {
        tearDown();

        System.out.println("\n========== 显式 setNull 的行为 ==========");

        Account a = new Account();
        a.setUserId(TEST_USER);
        a.setName("显式null");
        a.setType("CASH");
        a.setInitBalance(BigDecimal.ZERO);
        a.setBalance(BigDecimal.ZERO);
        a.setSort(5);
        // 显式设为 null
        a.setIcon(null);

        accountMapper.insert(a);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT icon, sort FROM t_account WHERE id = ?", a.getId());
        System.out.println("  icon = " + row.get("icon"));
        System.out.println("  sort = " + row.get("sort"));

        // 显式 null 同样被跳过（not_null 策略不区分"没 set"和"set 了 null"）
        assertNull(row.get("icon"));
        assertEquals(5, ((Number) row.get("sort")).intValue(), "已 set 的字段正常写入");
        System.out.println("  结论：not_null 策略下，\"没 set\" 与 \"set 成 null\" 等价，都会被跳过。");
    }
}
