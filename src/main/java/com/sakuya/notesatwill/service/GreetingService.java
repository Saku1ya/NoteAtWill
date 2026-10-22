package com.sakuya.notesatwill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sakuya.notesatwill.client.AiGreetingClient;
import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.enums.TimeBucket;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.DailyGreeting;
import com.sakuya.notesatwill.entity.DTO.ai.PythonGreetingRequest;
import com.sakuya.notesatwill.entity.DTO.ai.PythonGreetingResponse;
import com.sakuya.notesatwill.entity.VO.GreetingVO;
import com.sakuya.notesatwill.entity.VO.PersonaVO;
import com.sakuya.notesatwill.entity.VO.StatsSummaryVO;
import com.sakuya.notesatwill.mapper.DailyGreetingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;

/**
 * 每日问候服务：<b>当天首次打开时生成一条问候（一天只有一条）</b>。
 *
 * <p><b>★★ 幂等的实现方式（本类最重要的设计）</b>
 * <pre>
 *   ① 查当天有没有 → 有就直接复用（alreadySent = true）
 *   ② 没有 → 调 AI 生成 → INSERT
 *         └─ 若并发下被别人抢先插入（DuplicateKeyException）
 *              → 回头查出那一条并复用
 * </pre>
 * <b>唯一性由数据库唯一索引 {@code uk_user_date(user_id, greet_date)} 保证</b>，
 * 不靠"先查再插" —— 后者在并发下没有任何保证：
 * 两个请求同时查不到，就会插两条。
 *
 * <p><b>★★ 三层降级，保证首页不白屏</b>
 * <ol>
 *   <li>AI 3 秒内返回 → 用 AI 问候（{@code degraded=false}）</li>
 *   <li>AI 超时/不可用/内容不合法 → <b>模板兜底</b>（{@code degraded=true}），模板也带真实数字</li>
 *   <li>统计都查不到（极端）→ 纯文案问候，<b>绝不返回空</b></li>
 * </ol>
 * 首屏内容里，<b>"有内容的模板"永远优于"空白等待"</b>。
 *
 * <p><b>★ 本方法不加 {@code @Transactional}</b>：
 * 它要发起一次最长 3 秒的外部调用，包在事务里会让数据库连接白占 3 秒。
 * 实际写库只有一条 INSERT，单语句本身原子；并发安全由唯一索引保证。
 *
 * @author sakuya
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GreetingService {

    /** 模型名占位（仅用于区分"AI 生成"与"模板兜底"：为 null 即模板） */
    private static final String MODEL_PLACEHOLDER = "deepseek-flash";

    private final DailyGreetingMapper greetingMapper;
    private final AiGreetingClient aiGreetingClient;
    private final StatsService statsService;
    private final PersonaService personaService;
    private final BookService bookService;
    private final ChatMessageService chatMessageService;

    // ========================================================================
    // 主流程
    // ========================================================================

    /**
     * 当天首次打开页面时调用：<b>保证一天只有一条问候</b>。
     *
     * @param userId 当前登录用户
     * @return 问候（新生成的，或今天已有的）
     * @throws BusinessException 用户没有任何账本（3000）
     */
    public GreetingVO dailyOpen(Long userId) {
        LocalDate today = LocalDate.now();

        // ---------- ① 今天已有 → 直接复用，不调 AI ----------
        DailyGreeting existing = findToday(userId, today);
        if (existing != null) {
            log.debug("今日问候已存在，直接复用：userId={}, date={}", userId, today);
            return buildVO(existing, true);
        }

        // ---------- ② 统计与生成 ----------
        Long bookId = resolveDefaultBookId(userId);
        StatsSnapshot stats = loadStats(userId, bookId, today);
        Generated generated = generateGreeting(userId, stats);

        // ---------- ③ 落库（并发由唯一索引兜住）----------
        PersistResult persisted = insertOrGetExisting(userId, today, generated);

        // ---------- ④ 聊天流里也留一条（展示用，失败不影响问候）----------
        chatMessageService.appendGreeting(userId, bookId, persisted.row());

        GreetingVO vo = buildVO(persisted.row(), persisted.alreadyExisted());
        log.info("每日问候返回：userId={}, date={}, bucket={}, degraded={}, alreadySent={}",
                userId, today, vo.timeBucket(), vo.degraded(), vo.alreadySent());
        return vo;
    }

    /**
     * 查今天的问候（<b>不生成</b>）。供"只读"场景使用。
     *
     * @param userId 用户 id
     * @return 今天的问候；还没有则 {@code null}
     */
    public GreetingVO peekToday(Long userId) {
        DailyGreeting today = findToday(userId, LocalDate.now());
        return today == null ? null : buildVO(today, true);
    }

    // ========================================================================
    // 内部：统计快照
    // ========================================================================

    /**
     * 问候需要的统计数字（<b>全部来自 StatsService，走缓存</b>）。
     *
     * @param expense      本月支出
     * @param dailyAverage 日均支出
     * @param days         日均的分母天数
     * @param todayExpense 今天的支出
     * @param hasBillToday 今天是否已记账
     */
    private record StatsSnapshot(BigDecimal expense, BigDecimal dailyAverage,
                                 long days, BigDecimal todayExpense, boolean hasBillToday) {
    }

    /**
     * 取统计快照。<b>任何异常都返回全 0 快照，绝不抛出</b> ——
     * 问候是首屏内容，不能因为统计查询出错而失败。
     */
    private StatsSnapshot loadStats(Long userId, Long bookId, LocalDate today) {
        BigDecimal expense = BigDecimal.ZERO;
        BigDecimal dailyAverage = BigDecimal.ZERO;
        long days = 0;

        try {
            StatsSummaryVO summary = statsService.summary(userId, bookId, null, null);
            expense = nz(summary.expense());
            dailyAverage = nz(summary.dailyAverage());
            days = summary.days();
        } catch (Exception e) {
            log.warn("问候取本月统计失败（用 0 兜底）：userId={}, err={}", userId, e.getMessage());
        }

        BigDecimal todayExpense = BigDecimal.ZERO;
        try {
            StatsSummaryVO todaySummary = statsService.summary(userId, bookId, today, today);
            todayExpense = nz(todaySummary.expense());
        } catch (Exception e) {
            log.debug("问候取今日支出失败（忽略）：userId={}, err={}", userId, e.getMessage());
        }

        return new StatsSnapshot(expense, dailyAverage, days, todayExpense,
                todayExpense.compareTo(BigDecimal.ZERO) > 0);
    }

    // ========================================================================
    // 内部：生成
    // ========================================================================

    /**
     * 生成问候内容：<b>AI 优先，任何异常都降级为模板</b>。
     */
    private Generated generateGreeting(Long userId, StatsSnapshot stats) {
        TimeBucket bucket = TimeBucket.of(LocalTime.now());

        PersonaVO persona = null;
        try {
            persona = personaService.getActive(userId);
        } catch (Exception e) {
            log.debug("取角色失败（用中性语气）：userId={}", userId);
        }

        try {
            PythonGreetingResponse resp = aiGreetingClient.greeting(new PythonGreetingRequest(
                    LocalDateTime.now(),
                    bucket.name(),
                    weekdayOf(LocalDate.now()),
                    YearMonth.now().toString(),
                    stats.expense(),
                    stats.dailyAverage(),
                    stats.days(),
                    stats.todayExpense(),
                    stats.hasBillToday(),
                    persona == null ? null : persona.name(),
                    persona == null ? null : persona.personality(),
                    persona == null ? null : persona.relation(),
                    persona == null ? null : persona.address(),
                    persona == null ? null : persona.gender()));

            if (resp != null && Boolean.TRUE.equals(resp.success())
                    && resp.greeting() != null && !resp.greeting().isBlank()) {
                log.info("AI 问候生成成功：userId={}, bucket={}, {} 字",
                        userId, bucket, resp.greeting().length());
                return new Generated(bucket, resp.greeting().trim(), false);
            }
            log.warn("AI 问候未成功（模板兜底）：userId={}, reason={}",
                    userId, resp == null ? "空响应" : resp.error());
        } catch (Exception e) {
            // ★ 超时 / 连接失败 / 反序列化失败 —— 全部降级
            log.warn("AI 问候调用失败（模板兜底）：userId={}, err={}", userId, e.getMessage());
        }

        return new Generated(bucket, templateGreeting(bucket, stats), true);
    }

    /**
     * 模板问候：<b>AI 不可用时的兜底文案</b>。
     *
     * <p><b>★★ 模板里刻意不带任何金额</b>（2026-10-20 改）：
     * 问候一天只生成一次并缓存，而金额每分钟都在变 ——
     * 早上说"本月已花 200 元"，用户下午记了两笔后，同一屏右侧
     * 实时统计已经变成 500 元，两边对不上，用户会怀疑数据出了问题。
     * 金额交给首页右侧的统计面板（那里永远是实时值），
     * 问候只负责"打个招呼、提一句今天记没记"。
     */
    private String templateGreeting(TimeBucket bucket, StatsSnapshot stats) {
        String hello = switch (bucket) {
            case DAWN -> "凌晨了，还没睡呀";
            case MORNING -> "早上好";
            case FORENOON -> "上午好";
            case NOON -> "中午好";
            case AFTERNOON -> "下午好";
            case EVENING -> "傍晚好";
            case NIGHT -> "晚上好";
        };

        if (stats.expense().compareTo(BigDecimal.ZERO) == 0) {
            return hello + "！这个月还没有账单，要不要来第一笔？";
        }

        return stats.hasBillToday()
                ? hello + "！今天已经记过了，晚上记得收个尾。"
                : hello + "！今天还没记呢，今天花哪儿了？";
    }

    /** 星期几的中文写法（{@code DayOfWeek} 的 1 是星期一）。 */
    private String weekdayOf(LocalDate date) {
        return "星期" + switch (date.getDayOfWeek()) {
            case MONDAY -> "一";
            case TUESDAY -> "二";
            case WEDNESDAY -> "三";
            case THURSDAY -> "四";
            case FRIDAY -> "五";
            case SATURDAY -> "六";
            case SUNDAY -> "日";
        };
    }

    // ========================================================================
    // 内部：持久化与幂等
    // ========================================================================

    /**
     * 插入今天的问候；<b>若并发下已被别人插入，则查出那一条返回</b>。
     *
     * @param row             新插入的行
     * @param alreadyExisted  是否复用了别人插入的
     */
    private record PersistResult(DailyGreeting row, boolean alreadyExisted) {
    }

    private PersistResult insertOrGetExisting(Long userId, LocalDate today, Generated generated) {
        DailyGreeting row = new DailyGreeting();
        row.setUserId(userId);
        row.setGreetDate(today);
        row.setTimeBucket(generated.bucket().name());
        row.setContent(generated.content());
        // ★ modelName 为 null 即"模板兜底"（与 toVO 的 degraded 判定一致）
        row.setModelName(generated.degraded() ? null : MODEL_PLACEHOLDER);

        try {
            greetingMapper.insert(row);
            log.info("今日问候已生成：userId={}, date={}, bucket={}, degraded={}",
                    userId, today, generated.bucket(), generated.degraded());
            return new PersistResult(row, false);
        } catch (DuplicateKeyException e) {
            // ★ 并发：另一请求抢先插入 → 复用它的
            log.info("今日问候已被并发请求生成，复用已有记录：userId={}, date={}", userId, today);
            DailyGreeting existing = findToday(userId, today);
            if (existing == null) {
                // 极端：插入冲突却查不到（刚被删）→ 原样抛出，让调用方感知异常
                throw e;
            }
            return new PersistResult(existing, true);
        }
    }

    private DailyGreeting findToday(Long userId, LocalDate date) {
        return greetingMapper.selectOne(
                new LambdaQueryWrapper<DailyGreeting>()
                        .eq(DailyGreeting::getUserId, userId)
                        .eq(DailyGreeting::getGreetDate, date)
                        // ★ 历史脏数据若有多条（唯一索引已兜住，理论上不会），取最新一条，
                        //   避免 selectOne 抛 TooManyResultsException
                        .orderByDesc(DailyGreeting::getId)
                        .last("LIMIT 1"));
    }

    /** 取用户默认账本 id；没有账本时抛 3000。 */
    private Long resolveDefaultBookId(Long userId) {
        var books = bookService.list(userId);
        if (books == null || books.isEmpty()) {
            throw new BusinessException(ErrorCode.BOOK_NOT_FOUND);
        }
        return books.get(0).getId();
    }

    /**
     * 组装 VO。
     *
     * <p><b>★ {@code degraded} 由 {@code modelName} 是否为 null 推导</b>，
     * 而不是存在内存里 —— 这样<b>复用历史记录时也能正确标记</b>
     * （比如早上 AI 挂了用模板生成，下午再打开，仍应显示"降级"）。
     */
    private GreetingVO buildVO(DailyGreeting row, boolean alreadySent) {
        return new GreetingVO(
                row.getGreetDate(),
                row.getTimeBucket(),
                row.getContent(),
                row.getModelName() == null,
                alreadySent,
                null,
                null);
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 生成结果：区分 AI 与模板。 */
    private record Generated(TimeBucket bucket, String content, boolean degraded) {
    }
}
