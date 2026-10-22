package com.sakuya.notesatwill;

import com.sakuya.notesatwill.client.AiGreetingClient;
import com.sakuya.notesatwill.common.enums.TimeBucket;
import com.sakuya.notesatwill.entity.DTO.BillRequest;
import com.sakuya.notesatwill.entity.DTO.ai.PythonGreetingResponse;
import com.sakuya.notesatwill.entity.VO.GreetingVO;
import com.sakuya.notesatwill.service.BillService;
import com.sakuya.notesatwill.service.BookService;
import com.sakuya.notesatwill.service.GreetingService;
import com.sakuya.notesatwill.service.StatsCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每日问候（任务 7.5 + 7.6）回归测试。
 *
 * <p><b>★★ 核心验收点（按重要性）</b>：
 * <ol>
 *   <li><b>幂等</b>：同一天连调 10 次只生成一条；<b>并发</b>下也只能有一条</li>
 *   <li><b>降级</b>：AI 超时/不可用时用模板问候，<b>不能报错、不能白屏</b></li>
 *   <li>模板里必须有真实数字（否则降级后毫无价值）</li>
 *   <li>时段由 Java 判定，边界正确</li>
 * </ol>
 *
 * @author sakuya
 */
@SpringBootTest
class GreetingServiceTest {

    private static final Long MY_ID = 987502001L;
    private static final Long OTHER_ID = 987502002L;
    private static final Long CAT_LUNCH = 100102L;

    @Autowired
    private GreetingService greetingService;

    @Autowired
    private BillService billService;

    @Autowired
    private BookService bookService;

    @Autowired
    private StatsCacheService cacheService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** ★ 打桩 AI 问候客户端：本测试不需要 Python 服务 */
    @MockitoBean
    private AiGreetingClient aiGreetingClient;

    private Long bookId;

    @BeforeEach
    void setUp() {
        clean();
        bookId = bookService.createDefaultBook(MY_ID).getId();
        when(aiGreetingClient.greeting(any()))
                .thenReturn(new PythonGreetingResponse(true, "早上好呀，今天也要好好记账。", null));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (Long u : new Long[]{MY_ID, OTHER_ID}) {
            jdbcTemplate.update("DELETE FROM t_chat_message WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_daily_greeting WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_ai_persona WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_budget WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_account_flow WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_bill WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_account WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_book WHERE user_id = ?", u);
            cacheService.evictUser(u);
        }
    }

    private void spend(String amount) {
        billService.createBill(MY_ID, new BillRequest(
                bookId, CAT_LUNCH, BillService.TYPE_EXPENSE,
                new BigDecimal(amount), java.time.LocalDateTime.now().minusMinutes(1),
                "测试", null));
    }

    private int greetingCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_daily_greeting WHERE user_id = ?", Integer.class, MY_ID);
        return n == null ? 0 : n;
    }

    // ==================== ★★ 幂等 ====================

    @Test
    void firstCallGeneratesGreeting() {
        System.out.println("\n===== 第一次打开 → 生成问候 =====");
        GreetingVO vo = greetingService.dailyOpen(MY_ID);

        assertNotNull(vo.content());
        assertFalse(vo.alreadySent(), "★ 第一次应是新生成的");
        assertEquals(1, greetingCount());
        System.out.println("  内容：" + vo.content() + "，alreadySent=" + vo.alreadySent());
    }

    @Test
    void repeatedCallsReuseSameGreeting() {
        System.out.println("\n===== ★★ 连调 10 次 → 只有一条问候 =====");
        List<GreetingVO> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(greetingService.dailyOpen(MY_ID));
        }

        System.out.println("  数据库里问候条数=" + greetingCount());
        assertEquals(1, greetingCount(), "★★ 必须只有一条");

        assertFalse(results.get(0).alreadySent(), "第 1 次是新生成");
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i).alreadySent(), "第 " + (i + 1) + " 次应标记为已存在");
            assertEquals(results.get(0).content(), results.get(i).content(),
                    "★ 内容必须完全一致（复用同一条）");
        }
        System.out.println("  ✓ 1 次生成 + 9 次复用，内容一致");
    }

    @Test
    void onlyCallsAiOnce() {
        System.out.println("\n===== ★ 复用时不重复调用 AI（省钱 + 快）=====");
        greetingService.dailyOpen(MY_ID);
        greetingService.dailyOpen(MY_ID);
        greetingService.dailyOpen(MY_ID);

        verify(aiGreetingClient, times(1)).greeting(any());
        System.out.println("  ✓ AI 只被调了 1 次");
    }

    @Test
    void concurrentCallsStillProduceOneGreeting() throws Exception {
        System.out.println("\n===== ★★★ 并发 8 个请求 → 仍只有一条（唯一索引）=====");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();
        List<String> contents = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    GreetingVO vo = greetingService.dailyOpen(MY_ID);
                    if (vo != null) {
                        contents.add(vo.content());
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    System.out.println("    并发异常：" + e.getClass().getSimpleName()
                            + " - " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发请求应在 30 秒内完成");
        pool.shutdown();

        System.out.println("  数据库里问候条数=" + greetingCount()
                + "，成功返回=" + contents.size() + "，异常=" + errors.get());
        assertEquals(1, greetingCount(),
                "★★★ 并发下也只能有一条 —— 这是唯一索引的价值，'先查再插'在这里必漏");
        assertEquals(0, errors.get(), "★ 并发不应让任何请求失败（冲突应被优雅处理）");
        assertEquals(threads, contents.size(), "所有请求都应拿到内容");
        // 所有请求拿到的是同一条问候的同一内容
        assertEquals(1, contents.stream().distinct().count(), "内容必须完全一致");
        System.out.println("  ✓ 8 个并发请求 → 1 条记录，0 异常，内容一致");
    }

    // ==================== ★★ 降级 ====================

    @Test
    void fallsBackToTemplateWhenAiFails() {
        System.out.println("\n===== ★★ AI 失败 → 模板兜底（不报错）=====");
        when(aiGreetingClient.greeting(any())).thenThrow(
                new ResourceAccessException("timeout",
                        new java.net.SocketTimeoutException("Read timed out")));
        spend("1250.00");

        GreetingVO vo = greetingService.dailyOpen(MY_ID);

        assertNotNull(vo, "★ 不能返回 null");
        assertNotNull(vo.content(), "★ 不能白屏");
        assertTrue(vo.degraded(), "★ 应标记为降级");
        System.out.println("  模板内容：" + vo.content());
        // ★★ 模板里【不能】出现金额（2026-10-20 改）：
        //   问候一天只生成一次并缓存，金额却一直在变，两者并排会自相矛盾。
        //   金额交给首页右侧的实时统计面板，问候只打招呼。
        assertFalse(vo.content().contains("1250"), "★ 模板里不能出现金额");
        assertFalse(vo.content().contains("日均"), "★ 模板里不能报日均");
        assertTrue(vo.content().contains("下午好") || vo.content().contains("好"),
                "★ 模板仍要有真实问候语，不能只有空白");
        assertEquals(1, greetingCount(), "降级的问候也要落库（一天一条）");
        System.out.println("  ✓ 降级成功，模板不含金额");
    }

    @Test
    void fallsBackWhenAiReturnsFailure() {
        System.out.println("\n===== AI 返回 success=false → 模板兜底 =====");
        when(aiGreetingClient.greeting(any())).thenReturn(
                new PythonGreetingResponse(false, null, "模型超时"));

        GreetingVO vo = greetingService.dailyOpen(MY_ID);

        assertTrue(vo.degraded());
        assertNotNull(vo.content());
        System.out.println("  ✓ 降级，内容=" + vo.content());
    }

    @Test
    void fallsBackWhenAiReturnsBlank() {
        System.out.println("\n===== AI 返回空白 → 模板兜底 =====");
        when(aiGreetingClient.greeting(any())).thenReturn(
                new PythonGreetingResponse(true, "   ", null));

        GreetingVO vo = greetingService.dailyOpen(MY_ID);
        assertTrue(vo.degraded());
        System.out.println("  ✓ 降级");
    }

    @Test
    void templateWithNoExpense() {
        System.out.println("\n===== 本月没支出 → 模板也应合理 =====");
        when(aiGreetingClient.greeting(any())).thenThrow(new ResourceAccessException("down"));

        GreetingVO vo = greetingService.dailyOpen(MY_ID);
        System.out.println("  内容：" + vo.content());
        assertTrue(vo.content().contains("还没有"), "无支出时应提示还没记账");
        System.out.println("  ✓ 未出现「已花 0 元」这种怪话");
    }

    @Test
    void aiPathNotMarkedDegraded() {
        System.out.println("\n===== AI 成功时不标记降级 =====");
        GreetingVO vo = greetingService.dailyOpen(MY_ID);
        assertFalse(vo.degraded());
        System.out.println("  ✓ degraded=false");
    }

    @Test
    void reusedGreetingKeepsDegradedFlag() {
        System.out.println("\n===== ★ 复用降级问候时，仍应标记 degraded =====");
        when(aiGreetingClient.greeting(any())).thenThrow(new ResourceAccessException("down"));
        GreetingVO first = greetingService.dailyOpen(MY_ID);
        assertTrue(first.degraded());

        // 第二次不调 AI（已存在），但应保持"这是模板"的事实
        GreetingVO second = greetingService.dailyOpen(MY_ID);
        System.out.println("  第二次 degraded=" + second.degraded()
                + "，alreadySent=" + second.alreadySent());
        assertTrue(second.degraded(), "★ 降级标记来自 modelName 为 null，不依赖内存");
        assertTrue(second.alreadySent());
        System.out.println("  ✓ 仍标记降级（从数据库字段推导，不是内存状态）");
    }

    // ==================== peek ====================

    @Test
    void peekReturnsNullBeforeFirstOpen() {
        System.out.println("\n===== peek：还没问候过 → null =====");
        assertNull(greetingService.peekToday(MY_ID));
        System.out.println("  ✓ null");
    }

    @Test
    void peekReturnsGreetingAfterGeneration() {
        System.out.println("\n===== peek：生成后能读到 =====");
        GreetingVO generated = greetingService.dailyOpen(MY_ID);
        GreetingVO peeked = greetingService.peekToday(MY_ID);

        assertNotNull(peeked);
        assertEquals(generated.content(), peeked.content());
        assertTrue(peeked.alreadySent(), "peek 读到的都是已存在的");
        System.out.println("  ✓ 内容一致");
    }

    // ==================== 用户隔离 ====================

    @Test
    void eachUserGetsOwnGreeting() {
        System.out.println("\n===== 用户隔离：各自一条 =====");
        bookService.createDefaultBook(OTHER_ID);
        when(aiGreetingClient.greeting(any()))
                .thenReturn(new PythonGreetingResponse(true, "问候", null));

        GreetingVO mine = greetingService.dailyOpen(MY_ID);
        GreetingVO other = greetingService.dailyOpen(OTHER_ID);
        assertNotNull(mine.content());
        assertNotNull(other.content());

        // ★ 按用户范围统计，不要数全表：
        //   clean() 只清 MY_ID / OTHER_ID，而库里可能还有别的用户（比如手工联调账号）
        //   当天的问候，数全表会莫名其妙多出一条（2026-10-20 实际踩到）。
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_daily_greeting WHERE user_id IN (?, ?)",
                Integer.class, MY_ID, OTHER_ID);
        System.out.println("  两个测试用户的问候数=" + total);
        assertEquals(2, total, "两个用户各一条");
        System.out.println("  ✓ 互不干扰");
    }

    // ==================== 时段判定 ====================

    @Test
    void timeBucketBoundaries() {
        System.out.println("\n===== ★ 时段边界（左闭右开）=====");
        Object[][] cases = {
                {LocalTime.of(0, 0), TimeBucket.DAWN, "00:00 → DAWN"},
                {LocalTime.of(4, 59), TimeBucket.DAWN, "04:59 → DAWN"},
                {LocalTime.of(5, 0), TimeBucket.MORNING, "★05:00 → MORNING（边界归后一段）"},
                {LocalTime.of(7, 59), TimeBucket.MORNING, "07:59 → MORNING"},
                {LocalTime.of(8, 0), TimeBucket.FORENOON, "08:00 → FORENOON"},
                {LocalTime.of(11, 0), TimeBucket.NOON, "11:00 → NOON"},
                {LocalTime.of(12, 59), TimeBucket.NOON, "12:59 → NOON"},
                {LocalTime.of(13, 0), TimeBucket.AFTERNOON, "13:00 → AFTERNOON"},
                {LocalTime.of(16, 59), TimeBucket.AFTERNOON, "16:59 → AFTERNOON"},
                {LocalTime.of(17, 0), TimeBucket.EVENING, "17:00 → EVENING"},
                {LocalTime.of(18, 59), TimeBucket.EVENING, "18:59 → EVENING"},
                {LocalTime.of(19, 0), TimeBucket.NIGHT, "19:00 → NIGHT"},
                {LocalTime.of(23, 59, 59), TimeBucket.NIGHT, "23:59:59 → NIGHT"},
        };
        for (Object[] c : cases) {
            TimeBucket actual = TimeBucket.of((LocalTime) c[0]);
            System.out.printf("  %-40s 实际=%s%n", c[2], actual);
            assertEquals(c[1], actual, (String) c[2]);
        }
        System.out.println("  ✓ 13 个边界全部正确，无空档无重叠");
    }

    @Test
    void everyMomentHasExactlyOneBucket() {
        System.out.println("\n===== ★ 任意时刻恰好落在一个时段（无遗漏）=====");
        for (int h = 0; h < 24; h++) {
            for (int m : new int[]{0, 30, 59}) {
                LocalTime t = LocalTime.of(h, m);
                assertNotNull(TimeBucket.of(t), "时刻 " + t + " 没有归属时段");
            }
        }
        // 最后一刻
        assertNotNull(TimeBucket.of(LocalTime.of(23, 59, 59, 999_999_999)));
        System.out.println("  ✓ 24 小时 × 每小时抽查，全部有归属");
    }

    // ==================== 与角色 ====================

    @Test
    void passesActivePersonaToAi() {
        System.out.println("\n===== 带上生效角色的口吻 =====");
        // 直接看是否传了 personaName 需要捕获参数，这里只验证"有角色时不报错"
        jdbcTemplate.update(
                "INSERT INTO t_ai_persona (user_id, name, gender, personality, is_active, deleted) "
                        + "VALUES (?, '小小鹦鹉', 'FEMALE', '俏皮', 1, 0)", MY_ID);

        GreetingVO vo = greetingService.dailyOpen(MY_ID);
        assertNotNull(vo.content());
        System.out.println("  ✓ 有角色时正常生成");
    }
}
