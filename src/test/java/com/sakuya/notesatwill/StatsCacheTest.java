package com.sakuya.notesatwill;

import com.sakuya.notesatwill.entity.DTO.BillRequest;
import com.sakuya.notesatwill.entity.VO.StatsSummaryVO;
import com.sakuya.notesatwill.entity.VO.TrendVO;
import com.sakuya.notesatwill.service.BillService;
import com.sakuya.notesatwill.service.BookService;
import com.sakuya.notesatwill.service.StatsCacheService;
import com.sakuya.notesatwill.service.StatsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统计缓存（任务 6.4）回归测试。
 *
 * <p><b>★★ 本测试的重点不是"缓存能命中"，而是"缓存能被正确失效"</b>：
 * 缓存命中的 bug 是"性能没生效"（用户感知不到），
 * 而缓存<b>不失效</b>的 bug 是"数据错了"（用户改完账看不到变化）——
 * 后者才是灾难，而且它<b>不会让任何既有测试变红</b>，只能靠专门测试发现。
 *
 * <p>所以下面每个写操作（增/改/删/AI批量）都单独验一次"缓存被清掉了"。
 *
 * @author sakuya
 */
@SpringBootTest
class StatsCacheTest {

    private static final Long MY_ID = 987498001L;
    private static final Long CAT_LUNCH = 100102L;
    private static final Long CAT_TAXI = 100202L;

    @Autowired
    private StatsService statsService;

    @Autowired
    private StatsCacheService cacheService;

    @Autowired
    private BillService billService;

    @Autowired
    private BookService bookService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long bookId;

    @BeforeEach
    void setUp() {
        clean();
        bookId = bookService.createDefaultBook(MY_ID).getId();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM t_budget WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_account_flow WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_chat_message WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_bill WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_account WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_book WHERE user_id = ?", MY_ID);
        // ★ 清缓存：否则上个用例的统计缓存会串到下一个用例
        cacheService.evictUser(MY_ID);
    }


    private com.sakuya.notesatwill.entity.Bill addBill(LocalDate day, String amount, Long catId) {
        LocalDate today = LocalDate.now();
        java.time.LocalDateTime time = day.equals(today)
                ? java.time.LocalDateTime.now().minusMinutes(1)
                : day.atTime(12, 0);
        return billService.createBill(MY_ID, new BillRequest(
                bookId, catId, BillService.TYPE_EXPENSE,
                new BigDecimal(amount), time, "测试", null));
    }

    /** 该用户该账本下的缓存 key 数量 */
    private int cacheCount() {
        Set<String> keys = redisTemplate.keys("stats:" + MY_ID + ":" + bookId + ":*");
        return keys == null ? 0 : keys.size();
    }

    // ==================== 缓存命中 ====================

    @Test
    void summaryIsCachedAfterFirstCall() {
        System.out.println("\n===== 第一次查库，第二次命中缓存 =====");
        addBill(LocalDate.now().minusDays(1), "100.00", CAT_LUNCH);

        assertEquals(0, cacheCount(), "调用前没有缓存");

        statsService.summary(MY_ID, bookId, null, null);
        int afterFirst = cacheCount();
        statsService.summary(MY_ID, bookId, null, null);
        int afterSecond = cacheCount();

        System.out.println("  第一次后缓存数=" + afterFirst + "，第二次后=" + afterSecond);
        assertEquals(1, afterFirst, "第一次查询应写入 1 条缓存");
        assertEquals(1, afterSecond, "第二次应命中缓存，不再新增");
        System.out.println("  ✓ 缓存生效（不是每次都查库）");
    }

    @Test
    void cachedResultMatchesFreshResult() {
        System.out.println("\n===== 缓存返回的内容与查库一致 =====");
        addBill(LocalDate.now().minusDays(1), "250.00", CAT_LUNCH);

        StatsSummaryVO first = statsService.summary(MY_ID, bookId, null, null);
        StatsSummaryVO cached = statsService.summary(MY_ID, bookId, null, null);

        assertEquals(0, first.expense().compareTo(cached.expense()));
        assertEquals(first.days(), cached.days());
        assertEquals(first.maxExpenseDate(), cached.maxExpenseDate());
        System.out.println("  ✓ 金额=" + cached.expense() + "，days=" + cached.days() + "，完全一致");
    }

    @Test
    void differentRangesUseDifferentKeys() {
        System.out.println("\n===== 不同区间各用一条缓存（不会串）=====");
        addBill(LocalDate.now().minusDays(1), "100.00", CAT_LUNCH);

        statsService.summary(MY_ID, bookId, null, null);
        statsService.summary(MY_ID, bookId,
                LocalDate.now().minusMonths(1).withDayOfMonth(1),
                LocalDate.now().minusMonths(1).withDayOfMonth(
                        java.time.YearMonth.now().minusMonths(1).lengthOfMonth()));

        System.out.println("  缓存 key 数=" + cacheCount());
        assertEquals(2, cacheCount(), "两个不同区间应有两条缓存");
        System.out.println("  ✓ 未串键");
    }

    @Test
    void differentBooksUseDifferentKeys() {
        System.out.println("\n===== 不同账本各用一条缓存 =====");
        Long book2 = bookService.create(
                MY_ID, new com.sakuya.notesatwill.entity.DTO.BookRequest(
                        "账本二", null, null, null)).getId();

        statsService.summary(MY_ID, bookId, null, null);
        statsService.summary(MY_ID, book2, null, null);

        Set<String> book1Keys = redisTemplate.keys("stats:" + MY_ID + ":" + bookId + ":*");
        Set<String> book2Keys = redisTemplate.keys("stats:" + MY_ID + ":" + book2 + ":*");
        System.out.println("  book1 缓存=" + (book1Keys == null ? 0 : book1Keys.size())
                + "，book2 缓存=" + (book2Keys == null ? 0 : book2Keys.size()));
        assertEquals(1, book1Keys == null ? 0 : book1Keys.size());
        assertEquals(1, book2Keys == null ? 0 : book2Keys.size());
        System.out.println("  ✓ 按账本隔离");
    }

    // ==================== ★★ 缓存失效（核心）====================

    @Test
    void creatingBillEvictsCache() {
        System.out.println("\n===== ★★ 新增账单 → 缓存必须被清（第 1/4 处）=====");
        statsService.summary(MY_ID, bookId, null, null);
        assertEquals(1, cacheCount(), "先有 1 条缓存");

        addBill(LocalDate.now().minusDays(1), "100.00", CAT_LUNCH);

        System.out.println("  新增账单后缓存数=" + cacheCount());
        assertEquals(0, cacheCount(), "★ 新增账单必须清掉该账本的统计缓存");

        StatsSummaryVO after = statsService.summary(MY_ID, bookId, null, null);
        assertEquals(0, after.expense().compareTo(new BigDecimal("100.00")),
                "★ 重新查应反映新增的账单");
        System.out.println("  ✓ 缓存已清，重新查得到 100.00");
    }

    @Test
    void updatingBillEvictsCache() {
        System.out.println("\n===== ★★ 修改账单 → 缓存必须被清（第 3/4 处）=====");
        var bill = addBill(LocalDate.now().minusDays(1), "100.00", CAT_LUNCH);

        statsService.summary(MY_ID, bookId, null, null);
        assertEquals(1, cacheCount());

        billService.updateBill(MY_ID, bill.getId(), new BillRequest(
                bookId, CAT_LUNCH, BillService.TYPE_EXPENSE,
                new BigDecimal("999.00"), bill.getBillTime(), "改过", null));

        assertEquals(0, cacheCount(), "★ 修改账单必须清缓存");
        StatsSummaryVO after = statsService.summary(MY_ID, bookId, null, null);
        assertEquals(0, after.expense().compareTo(new BigDecimal("999.00")),
                "★ 重新查应反映新金额");
        System.out.println("  ✓ 缓存已清，金额变为 999.00");
    }

    @Test
    void deletingBillEvictsCache() {
        System.out.println("\n===== ★★ 删除账单 → 缓存必须被清（第 4/4 处）=====");
        var bill = addBill(LocalDate.now().minusDays(1), "100.00", CAT_LUNCH);

        statsService.summary(MY_ID, bookId, null, null);
        assertEquals(1, cacheCount());

        billService.deleteBill(MY_ID, bill.getId());

        assertEquals(0, cacheCount(), "★ 删除账单必须清缓存");
        StatsSummaryVO after = statsService.summary(MY_ID, bookId, null, null);
        assertEquals(0, after.expense().compareTo(BigDecimal.ZERO), "★ 删完后支出应为 0");
        System.out.println("  ✓ 缓存已清，支出回到 0");
    }

    @Test
    void aiConfirmEvictsCache() {
        System.out.println("\n===== ★ AI 批量落库也要清缓存（第 2/4 处）=====");
        statsService.summary(MY_ID, bookId, null, null);
        assertEquals(1, cacheCount());

        // 直接调 confirmAiBills（跳过 AiService.confirm 的日志校验，只测缓存）
        java.time.LocalDateTime t = java.time.LocalDateTime.now().minusMinutes(1);
        billService.confirmAiBills(MY_ID, 999999L, bookId, java.util.List.of(
                new com.sakuya.notesatwill.entity.DTO.AiBillItem(
                        new BigDecimal("66.00"), "EXPENSE", CAT_LUNCH, null,
                        null, null, t, "AI", new BigDecimal("0.9"))));

        assertEquals(0, cacheCount(), "★ AI 批量落库必须清缓存");
        System.out.println("  ✓ 缓存已清");
    }

    @Test
    void evictOnlyAffectsTargetBook() {
        System.out.println("\n===== ★ 清缓存只影响目标账本（不误伤其它账本）=====");
        Long book2 = bookService.create(
                MY_ID, new com.sakuya.notesatwill.entity.DTO.BookRequest(
                        "账本二", null, null, null)).getId();

        statsService.summary(MY_ID, bookId, null, null);
        statsService.summary(MY_ID, book2, null, null);
        assertEquals(1, redisTemplate.keys("stats:" + MY_ID + ":" + bookId + ":*").size());
        assertEquals(1, redisTemplate.keys("stats:" + MY_ID + ":" + book2 + ":*").size());

        // 只往 book1 记一笔
        billService.createBill(MY_ID, new BillRequest(
                bookId, CAT_LUNCH, BillService.TYPE_EXPENSE,
                new BigDecimal("50.00"), java.time.LocalDateTime.now().minusMinutes(1),
                "测试", null));

        Set<String> b1 = redisTemplate.keys("stats:" + MY_ID + ":" + bookId + ":*");
        Set<String> b2 = redisTemplate.keys("stats:" + MY_ID + ":" + book2 + ":*");
        System.out.println("  book1 缓存=" + (b1 == null ? 0 : b1.size())
                + "，book2 缓存=" + (b2 == null ? 0 : b2.size()));
        assertEquals(0, b1 == null ? 0 : b1.size(), "book1 应被清空");
        assertEquals(1, b2 == null ? 0 : b2.size(), "★ book2 的缓存不该被误删");
        System.out.println("  ✓ 只清了 book1，book2 仍命中");
    }

    // ==================== key 设计 ====================

    @Test
    void keyIsReadable() {
        System.out.println("\n===== key 可读（便于 redis-cli 排查）=====");
        String key = cacheService.key(MY_ID, bookId, "summary",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        System.out.println("  " + key);
        assertTrue(key.startsWith("stats:" + MY_ID + ":" + bookId + ":"));
        assertTrue(key.contains("2026-10-01"));
        assertTrue(key.contains("2026-10-31"));
        System.out.println("  ✓ 可读出用户/账本/区间");
    }

    @Test
    void nullParamsGetPlaceholder() {
        System.out.println("\n===== null 参数用占位符（避免撞键）=====");
        String k1 = cacheService.key(MY_ID, bookId, "trend", null, "A");
        String k2 = cacheService.key(MY_ID, bookId, "trend", "A", null);
        System.out.println("  " + k1);
        System.out.println("  " + k2);
        assertFalse(k1.equals(k2), "★ 位置不同的 null 不能拼出同一个 key");
        System.out.println("  ✓ 占位符生效");
    }

    // ==================== 降级 ====================

    @Test
    void badCacheContentFallsBackToDatabase() {
        System.out.println("\n===== ★ 缓存内容损坏 → 按未命中处理（不报错）=====");
        addBill(LocalDate.now().minusDays(1), "123.00", CAT_LUNCH);

        // 先算一次拿到真实 key，然后塞垃圾
        statsService.summary(MY_ID, bookId, null, null);
        Set<String> keys = redisTemplate.keys("stats:" + MY_ID + ":" + bookId + ":*");
        assertNotNull(keys);
        assertTrue(!keys.isEmpty());
        redisTemplate.opsForValue().set(keys.iterator().next(), "这不是合法 JSON {{{");

        StatsSummaryVO vo = statsService.summary(MY_ID, bookId, null, null);
        System.out.println("  读到坏缓存后仍返回：支出=" + vo.expense());
        assertEquals(0, vo.expense().compareTo(new BigDecimal("123.00")),
                "★ 坏缓存应被当作未命中，回退查库，而不是抛异常");
        System.out.println("  ✓ 优雅降级");
    }

    @Test
    void trendIsAlsoCached() {
        System.out.println("\n===== 趋势与占比也走缓存 =====");
        addBill(LocalDate.now().minusDays(1), "100.00", CAT_LUNCH);

        TrendVO t1 = statsService.trend(MY_ID, bookId, "EXPENSE", null, null, "DAY");
        int c1 = cacheCount();
        TrendVO t2 = statsService.trend(MY_ID, bookId, "EXPENSE", null, null, "DAY");

        assertEquals(c1, cacheCount(), "第二次不该新增缓存");
        assertEquals(t1.points().size(), t2.points().size());
        assertTrue(c1 >= 1);
        System.out.println("  ✓ 趋势已缓存，" + c1 + " 条 key");
    }

    @Test
    void granularityAffectsKey() {
        System.out.println("\n===== 不同粒度用不同 key =====");
        statsService.trend(MY_ID, bookId, "EXPENSE", null, null, "DAY");
        statsService.trend(MY_ID, bookId, "EXPENSE", null, null, "MONTH");
        System.out.println("  缓存数=" + cacheCount());
        assertEquals(2, cacheCount(), "两个粒度应是两条缓存");
        System.out.println("  ✓ 粒度参与 key");
    }
}
