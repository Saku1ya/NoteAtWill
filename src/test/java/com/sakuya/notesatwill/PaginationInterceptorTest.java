package com.sakuya.notesatwill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sakuya.notesatwill.entity.Account;
import com.sakuya.notesatwill.mapper.AccountMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分页拦截器回归测试。
 *
 * <p><b>为什么必须为它写测试</b>：分页拦截器漏配时<b>不会报错</b> ——
 * {@code selectPage} 照样返回数据，只是 {@code records} 里是<b>全表数据</b>，
 * 而 total 也是错的。这个故障是静默的，只有实际翻页时才发现
 * "第一页怎么返回了几百条"。
 *
 * <p>验证三件事：
 * <ol>
 *   <li>拦截器 Bean 确实被注册进了容器</li>
 *   <li>{@code selectPage} 真的只取一页（不是全量）</li>
 *   <li>{@code total} 是真实总数（分页插件会额外发一条 count 查询）</li>
 * </ol>
 *
 * @author sakuya
 */
@SpringBootTest
class PaginationInterceptorTest {

    private static final Long TEST_USER = 987600001L;
    private static final int TOTAL = 25;      // 造 25 条数据
    private static final int PAGE_SIZE = 10;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext ctx;

    @BeforeEach
    void setUp() {
        tearDown();
        for (int i = 1; i <= TOTAL; i++) {
            Account a = new Account();
            a.setUserId(TEST_USER);
            a.setName("分页测试-" + String.format("%02d", i));
            a.setType("CASH");
            a.setInitBalance(new BigDecimal("100.00"));
            a.setBalance(new BigDecimal("100.00"));
            a.setSort(i);
            accountMapper.insert(a);
        }
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM t_account WHERE user_id = ?", TEST_USER);
    }

    @Test
    void interceptorBeanIsRegistered() {
        System.out.println("\n===== 拦截器 Bean 是否注册 =====");

        MybatisPlusInterceptor interceptor = ctx.getBean(MybatisPlusInterceptor.class);
        assertNotNull(interceptor, "MybatisPlusInterceptor 应存在于容器中");

        List<InnerInterceptor> inners = interceptor.getInterceptors();
        System.out.println("  内部拦截器数量 = " + inners.size());
        inners.forEach(i -> System.out.println("    - " + i.getClass().getSimpleName()));

        boolean hasPagination = inners.stream().anyMatch(i -> i instanceof PaginationInnerInterceptor);
        assertTrue(hasPagination, "★ 必须包含 PaginationInnerInterceptor");
        System.out.println("  ✓ 分页拦截器已注册");

        // 提醒：分页插件必须是【最后一个】内部拦截器。
        // 因为分页会改写 SQL，若它之后还有其他会改写 SQL 的插件，
        // 分页计算出的 limit 可能被后续插件破坏。
        int pageIdx = -1;
        for (int i = 0; i < inners.size(); i++) {
            if (inners.get(i) instanceof PaginationInnerInterceptor) {
                pageIdx = i;
            }
        }
        System.out.println("  分页插件位置 = " + pageIdx + " / " + (inners.size() - 1)
                + (pageIdx == inners.size() - 1 ? "（在最后，符合要求）" : "（★ 建议移到最后一个）"));
    }

    @Test
    void selectPageReturnsOnlyOnePage() {
        System.out.println("\n===== 分页真的只取一页 =====");

        Page<Account> page = accountMapper.selectPage(
                Page.of(1, PAGE_SIZE),
                new LambdaQueryWrapper<Account>()
                        .eq(Account::getUserId, TEST_USER)
                        .orderByAsc(Account::getSort)
        );

        System.out.printf("  总数据 %d 条，请求第 1 页 / 每页 %d 条%n", TOTAL, PAGE_SIZE);
        System.out.printf("  返回 records=%d 条，total=%d，pages=%d%n",
                page.getRecords().size(), page.getTotal(), page.getPages());

        // ★ 核心断言：不是全量
        assertEquals(PAGE_SIZE, page.getRecords().size(),
                "★ 应只返回 " + PAGE_SIZE + " 条，实际 " + page.getRecords().size()
                        + " 条 —— 若等于 " + TOTAL + " 说明分页拦截器【没生效】");
        assertEquals(TOTAL, page.getTotal(), "total 应是真实总数");
        assertEquals(3, page.getPages(), "25 条 ÷ 每页 10 = 3 页");
        System.out.println("  ✓ 返回 10 条 / 共 25 条 / 3 页");

        // 第 1 页的数据应是 sort=1..10
        assertTrue(page.getRecords().get(0).getSort() == 1);
        assertEquals(10, page.getRecords().get(9).getSort());
        System.out.println("  ✓ 第 1 页内容是 sort=1..10（排序正确）");
    }

    @Test
    void lastPageReturnsRemainder() {
        System.out.println("\n===== 最后一页返回余数 =====");

        Page<Account> page = accountMapper.selectPage(
                Page.of(3, PAGE_SIZE),
                new LambdaQueryWrapper<Account>()
                        .eq(Account::getUserId, TEST_USER)
                        .orderByAsc(Account::getSort)
        );

        System.out.printf("  第 3 页 records=%d 条（25 = 10 + 10 + 5）%n", page.getRecords().size());
        assertEquals(5, page.getRecords().size(), "最后一页应有 5 条");
        assertEquals(21, page.getRecords().get(0).getSort(), "最后一页从 sort=21 开始");
        System.out.println("  ✓ 余数正确（5 条，从 sort=21 开始）");
    }

    @Test
    void outOfRangePageReturnsEmptyNotAll() {
        System.out.println("\n===== 超出范围的页码 =====");

        Page<Account> page = accountMapper.selectPage(
                Page.of(99, PAGE_SIZE),
                new LambdaQueryWrapper<Account>().eq(Account::getUserId, TEST_USER)
        );

        System.out.printf("  第 99 页 records=%d 条，total=%d%n",
                page.getRecords().size(), page.getTotal());
        // 关键：越界应返回空集，而不是退化成全量
        assertEquals(0, page.getRecords().size(), "越界页应为空，绝不能退化成全量");
        assertEquals(TOTAL, page.getTotal(), "total 仍应正确");
        System.out.println("  ✓ 越界返回空集，未退化为全量（证明分页真实生效）");
    }

    @Test
    void sizeIsHonoredExactly() {
        System.out.println("\n===== 自定义每页条数 =====");

        for (int size : new int[]{1, 7, 25, 100}) {
            Page<Account> page = accountMapper.selectPage(
                    Page.of(1, size),
                    new LambdaQueryWrapper<Account>().eq(Account::getUserId, TEST_USER)
            );
            int expected = Math.min(size, TOTAL);
            System.out.printf("  size=%-4d → records=%d（期望 %d）%n", size, page.getRecords().size(), expected);
            assertEquals(expected, page.getRecords().size(), "size=" + size + " 时应返回 " + expected + " 条");
        }
        System.out.println("  ✓ 各种 size 都精确生效（含 size > 总数 的情况）");
    }
}
