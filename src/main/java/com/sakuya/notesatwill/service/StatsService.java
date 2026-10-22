package com.sakuya.notesatwill.service;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.entity.VO.PersonaVO;
import com.sakuya.notesatwill.entity.VO.AiCommentVO;
import com.sakuya.notesatwill.entity.DTO.ai.PythonAnalyzeResponse;
import com.sakuya.notesatwill.entity.DTO.ai.PythonAnalyzeRequest;
import com.sakuya.notesatwill.client.AiAnalyzeClient;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Book;
import com.sakuya.notesatwill.entity.DTO.row.CategorySumRow;
import com.sakuya.notesatwill.entity.DTO.row.DaySumRow;
import com.sakuya.notesatwill.entity.DTO.row.MaxExpenseRow;
import com.sakuya.notesatwill.entity.VO.CategoryRatioVO;
import com.sakuya.notesatwill.entity.VO.CategoryTreeVO;
import com.sakuya.notesatwill.entity.VO.StatsSummaryVO;
import com.sakuya.notesatwill.entity.VO.TrendVO;
import com.sakuya.notesatwill.mapper.BillMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 统计服务：类型占比（饼图）与趋势（折线图）。
 *
 * <p><b>★★ 全项目「统计口径」的唯一定义处。</b>
 * 预算、统计、AI 点评三处都要"本月支出"这个数，若各写一份聚合，
 * 会出现"预算说已用 1200、占比图算出 1180、AI 点评说 1150"。
 * 所以：
 * <ul>
 *   <li>区间一律由 {@code LocalDate start/end} 给出，转成 {@link LocalDateTime} 时
 *       <b>左闭右开</b>（{@code >= start 00:00} 且 {@code < end+1天 00:00}）</li>
 *   <li>聚合一律走 {@link BillMapper#sumGroupByCategory} 与
 *       {@link BillMapper#sumGroupByDay}，<b>不在这里手写 SQL</b></li>
 * </ul>
 *
 * <p><b>★ 为什么日/周/月三种趋势都从"按天"的数据聚合出来</b>：
 * 一次按天查询，三种粒度在内存里合并 —— <b>口径天然一致</b>
 * （"日趋势加起来"必然等于"月趋势"）。若各自写一条 {@code GROUP BY}，
 * 周按 ISO 周、月按自然月，边界处理稍有差异就会对不上。
 * 一个月的按天数据最多 31 行，内存开销可忽略。
 *
 * @author sakuya
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    /** 粒度：按天 */
    public static final String GRANULARITY_DAY = "DAY";
    /** 粒度：按周（ISO 周，周一为起点） */
    public static final String GRANULARITY_WEEK = "WEEK";
    /** 粒度：按月 */
    public static final String GRANULARITY_MONTH = "MONTH";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** 传给 AI 点评的分类占比条数上限 */
    private static final int AI_COMMENT_TOP_N = 5;

    /**
     * AI 点评缓存时长（秒）。
     *
     * <p><b>★ 为什么不是统计缓存那个 60 秒</b>：
     * 点评调一次大模型成本高（token + 十几秒），60 秒就过期等于没缓存。
     * 10 分钟内同一区间重复请求直接命中。
     * <p><b>★ 账单变化会自动失效</b>：账单写操作会调 {@code evictBook} 清掉整个前缀，
     * 所以"记了新账后点评过时"不依赖 TTL 兜底。
     */
    private static final long AI_COMMENT_TTL_SECONDS = 600;

    private final BillMapper billMapper;
    private final BookService bookService;
    private final CategoryService categoryService;
    private final StatsCacheService cacheService;
    private final AiAnalyzeClient aiAnalyzeClient;
    private final PersonaService personaService;

    // ========================================================================
    // 6.3 区间汇总
    // ========================================================================

    /**
     * 区间汇总：总收入、总支出、结余、日均、最大单笔。
     *
     * <p><b>★★ 日均的分母为什么不是「区间总天数」（本方法最容易做错的地方）</b>：
     * <pre>
     *   今天 10-19，查 10 月（10-01 ~ 10-31）
     *
     *   ❌ 用总天数 31：日均 = 总支出 / 31
     *      → 后 12 天还没过，钱还没花，日均被严重低估
     *      → 用户看到"日均 32 元"会觉得数据错了（实际每天花 52 元）
     *
     *   ✅ 用已过天数 19：日均 = 总支出 / 19
     *      → 符合"这个月到现在平均每天花多少"的直觉
     * </pre>
     * <b>历史区间</b>（end 早于今天）则用完整天数 —— 那个月已经过完了。
     * <b>查询未来区间</b>（start 晚于今天）时天数为 0，日均返回 0（不除零）。
     *
     * <p><b>★ 为什么"最大单笔"单独查一次</b>：{@code sumAmount} 只给合计，
     * 拿不到"哪一笔最大"。用 {@code ORDER BY amount DESC LIMIT 1} 一次拿全字段
     * （见 {@link BillMapper#findMaxAmount}），比"先 MAX 再回查"简洁且不会因同额返回多条。
     *
     * <p><b>★ 聚合口径与其他统计接口完全一致</b>：都走
     * {@link BillMapper#sumAmount}，区间都是左闭右开，
     * 所以"汇总卡片的支出"必然等于"饼图各分类之和"。
     *
     * @param userId 当前登录用户
     * @param bookId 账本
     * @param start  区间起点（含）；不传取当月 1 日
     * @param end    区间终点（<b>含</b>）；不传取当月末日
     * @return 区间汇总
     */
    public StatsSummaryVO summary(Long userId, Long bookId, LocalDate start, LocalDate end) {
        // ★ 归属校验放在缓存【外面】：越权检查不能被缓存绕过
        Book book = bookService.getOwned(userId, bookId);
        LocalDate[] range = normalizeRange(start, end);
        LocalDate from = range[0];
        LocalDate to = range[1];

        // ★ 缓存 key 只包含影响结果的因素：区间（账本已在 key 前缀里）
        String key = cacheService.key(userId, bookId, "summary", from, to);
        return cacheService.getOrLoad(key, StatsSummaryVO.class,
                () -> doSummary(userId, book, from, to));
    }

    /**
     * 实际查库的汇总逻辑（被缓存包裹）。
     *
     * <p><b>★ 为什么要把"归属校验"和"查库"分开</b>：
     * 校验必须在缓存<b>之前</b>执行 —— 否则命中缓存时就不做越权检查了，
     * 用户换一个 bookId 可能直接拿到别人账本的缓存结果（虽然 key 里有 userId，
     * 但"缓存命中就跳过校验"这个模式本身就是危险的，不该留下这种习惯）。
     */
    private StatsSummaryVO doSummary(Long userId, Book book, LocalDate from, LocalDate to) {
        Long bookId = book.getId();

        // 左闭右开：[from 00:00, to+1天 00:00)
        LocalDateTime fromTime = from.atStartOfDay();
        LocalDateTime toTime = to.plusDays(1).atStartOfDay();

        BigDecimal income = nullToZero(billMapper.sumAmount(
                userId, bookId, BillService.TYPE_INCOME, fromTime, toTime));
        BigDecimal expense = nullToZero(billMapper.sumAmount(
                userId, bookId, BillService.TYPE_EXPENSE, fromTime, toTime));

        long days = countDaysForAverage(from, to);
        BigDecimal dailyAverage = days <= 0
                ? ZERO
                : expense.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);

        MaxExpenseRow max = billMapper.findMaxAmount(
                userId, bookId, BillService.TYPE_EXPENSE, fromTime, toTime);

        // 最大单笔的分类名：用 nameMap 批量查（只有 1 个 id，但复用同一套"查不到就缺席"的容错）。
        // ★ 分类可能已被删除 —— 这时 name 为 null，前端显示"已删除分类"即可，不能因此报错。
        String maxCategoryName = null;
        if (max != null && max.getCategoryId() != null) {
            maxCategoryName = categoryService.nameMap(Set.of(max.getCategoryId()))
                    .get(max.getCategoryId());
        }

        log.debug("区间汇总（查库）：userId={}, bookId={}, {}~{}, days={}, 收入={}, 支出={}, 最大单笔={}",
                userId, bookId, from, to, days, income, expense,
                max == null ? null : max.getAmount());

        return new StatsSummaryVO(
                bookId,
                book.getName(),
                from,
                to,
                days,
                income,
                expense,
                income.subtract(expense),
                dailyAverage,
                max == null ? null : max.getAmount(),
                max == null ? null : max.getBillTime().toLocalDate(),
                maxCategoryName);
    }

    /**
     * 计算日均的分母天数。
     *
     * <p><b>规则（三条，按顺序判断）</b>：
     * <ol>
     *   <li>{@code to} 早于今天 → 区间<b>已结束</b>，用完整天数
     *       （那个月过完了，全部天数都是有效的）</li>
     *   <li>{@code from} 晚于今天 → 区间<b>还没开始</b>，返回 0（日均无意义，不除零）</li>
     *   <li>否则区间<b>正在进行中</b> → 用 {@code from} 到<b>今天</b>的天数（含两端）</li>
     * </ol>
     *
     * <p><b>★ 为什么"含两端"</b>：10-01 到 10-19 共 19 天，不是 18 天。
     * 差一天会让日均偏大约 5%，而且是"看起来对但就是不对"的那种错。
     */
    private long countDaysForAverage(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();

        if (to.isBefore(today)) {
            // 已结束的区间：完整天数（含两端）
            return ChronoUnit.DAYS.between(from, to) + 1;
        }
        if (from.isAfter(today)) {
            // 还没开始：没有有效天数
            return 0;
        }
        // 进行中：从 from 到今天就够了（含两端）
        return ChronoUnit.DAYS.between(from, today) + 1;
    }

    // ========================================================================
    // 6.1 类型占比
    // ========================================================================

    /**
     * 类型占比：按<b>一级分类</b>聚合，可选下钻到二级。
     *
     * <p><b>★ 为什么要把二级的金额上卷到一级</b>：
     * {@code t_bill.category_id} 存的是叶子分类（用户记的是「餐饮/午餐」），
     * 直接分组会得到一堆碎片（午餐 300、晚餐 200、早餐 50…），
     * 饼图会出现十几个小扇区。上卷到「餐饮」才是一张可读的饼图。
     *
     * <p><b>★ 上卷在 Java 里做</b>：需要 {@code t_category.parent_id} 的映射，
     * 用 {@link CategoryService#tree} 一次拿到两级结构即可；
     * 写成 SQL 要么 {@code CASE WHEN}（分类一多就没法看），要么自连接（更难维护）。
     *
     * <p><b>★ 孤儿分类的处理</b>：账单的分类若不在当前用户的分类树里
     * （例如分类已被删除），仍会作为独立的一级条目返回，名字为 {@code null}。
     * <b>不能丢弃</b> —— 否则"各分类金额相加 = 总支出"这条验收会失败。
     *
     * <p><b>★ percent 的分母统一是"总支出"</b>（不是父分类金额）：
     * 这样一二级的百分比可以直接横向比较，且一级之和 = 100%，
     * 二级之和 = 它所在一级的百分比（除非有账单直接记在一级上）。
     *
     * @param userId    当前登录用户
     * @param bookId    账本
     * @param type      收支类型（EXPENSE / INCOME），不传默认 EXPENSE
     * @param start     区间起点（含）
     * @param end       区间终点（<b>含</b>）
     * @param drillDown 是否返回二级明细
     * @return 一级分类占比列表，按金额降序
     */
    public List<CategoryRatioVO> categoryRatio(Long userId, Long bookId, String type,
                                               LocalDate start, LocalDate end,
                                               boolean drillDown) {
        // ★ 校验与参数归一化在缓存之前完成（归一化后的参数才进 key）
        bookService.getOwned(userId, bookId);
        String billType = normalizeType(type);
        LocalDate[] range = normalizeRange(start, end);

        String key = cacheService.key(userId, bookId, "ratio",
                billType, range[0], range[1], drillDown);
        return cacheService.getOrLoad(key,
                new tools.jackson.core.type.TypeReference<List<CategoryRatioVO>>() { },
                () -> doCategoryRatio(userId, bookId, billType, range[0], range[1], drillDown));
    }

    /** 实际查库的占比逻辑（被缓存包裹）。 */
    private List<CategoryRatioVO> doCategoryRatio(Long userId, Long bookId, String billType,
                                                  LocalDate start, LocalDate end,
                                                  boolean drillDown) {
        LocalDate[] range = new LocalDate[]{start, end};

        List<CategorySumRow> rows = billMapper.sumGroupByCategory(
                userId, bookId, billType, range[0].atStartOfDay(), range[1].plusDays(1).atStartOfDay());
        if (rows.isEmpty()) {
            return List.of();
        }

        // ---- 建两级映射：叶子 → 一级；一级 → (二级 → 名字) ----
        Map<Long, Long> rootOf = new HashMap<>();
        Map<Long, String> l1Name = new LinkedHashMap<>();
        Map<Long, Map<Long, String>> childrenOfL1 = new LinkedHashMap<>();
        for (CategoryTreeVO l1 : categoryService.tree(userId, billType)) {
            l1Name.put(l1.id(), l1.name());
            rootOf.put(l1.id(), l1.id());

            Map<Long, String> children = new LinkedHashMap<>();
            if (l1.children() != null) {
                for (CategoryTreeVO l2 : l1.children()) {
                    rootOf.put(l2.id(), l1.id());
                    children.put(l2.id(), l2.name());
                }
            }
            childrenOfL1.put(l1.id(), children);
        }

        // 兜底名字（孤儿分类 / 分类树里没有的）
        Set<Long> leafIds = rows.stream().map(CategorySumRow::getCategoryId).collect(java.util.stream.Collectors.toSet());
        Map<Long, String> fallbackNames = categoryService.nameMap(leafIds);

        // ---- 聚合：一级总量 + 各一级下二级明细 ----
        BigDecimal total = ZERO;
        Map<Long, BigDecimal> amountByRoot = new LinkedHashMap<>();
        Map<Long, Map<Long, BigDecimal>> amountByLeafUnderRoot = new HashMap<>();

        for (CategorySumRow row : rows) {
            BigDecimal amount = row.getTotal() == null ? ZERO : row.getTotal();
            total = total.add(amount);

            Long leaf = row.getCategoryId();
            // 找不到父 → 自己当作一级（孤儿分类）
            Long root = rootOf.getOrDefault(leaf, leaf);

            amountByRoot.merge(root, amount, BigDecimal::add);
            amountByLeafUnderRoot
                    .computeIfAbsent(root, k -> new HashMap<>())
                    .merge(leaf, amount, BigDecimal::add);
        }

        if (total.compareTo(ZERO) == 0) {
            return List.of();
        }

        // ---- 组装 VO（按金额降序）----
        final BigDecimal finalTotal = total;
        List<CategoryRatioVO> result = new ArrayList<>(amountByRoot.size());
        for (Map.Entry<Long, BigDecimal> entry : amountByRoot.entrySet()) {
            Long root = entry.getKey();
            BigDecimal amount = entry.getValue();
            String name = l1Name.getOrDefault(root, fallbackNames.get(root));

            List<CategoryRatioVO> children = List.of();
            if (drillDown) {
                Map<Long, String> childNames = childrenOfL1.getOrDefault(root, Map.of());
                Map<Long, BigDecimal> leafAmounts = amountByLeafUnderRoot.getOrDefault(root, Map.of());

                children = leafAmounts.entrySet().stream()
                        // 排除"账单直接记在一级分类上"那一项 —— 它已经体现在父级金额里，
                        // 再列一次会变成"父级下面挂一个同名的子级"
                        .filter(e -> !Objects.equals(e.getKey(), root))
                        .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed())
                        .map(e -> CategoryRatioVO.leaf(
                                e.getKey(),
                                childNames.getOrDefault(e.getKey(), fallbackNames.get(e.getKey())),
                                e.getValue(),
                                percentOf(e.getValue(), finalTotal)))
                        .toList();
            }

            result.add(new CategoryRatioVO(root, name, amount, percentOf(amount, finalTotal), children));
        }

        result.sort(Comparator.comparing(CategoryRatioVO::amount).reversed());
        return result;
    }

    // ========================================================================
    // 6.2 趋势
    // ========================================================================

    /**
     * 趋势：按日 / 周 / 月分组，<b>时间轴连续、无数据补 0</b>。
     *
     * <p><b>★★ 为什么必须补 0</b>：只返回有数据的点时，折线会"跨过"空白期 ——
     * 10-01 花了 100、10-05 花了 50，图上看像连续两天花了 100 和 50，
     * 实际中间三天是 0。<b>补 0 在 Service 层做</b>，因为补多少个点取决于
     * 查询区间与粒度（月份天数、跨月周数），只有 Java 的日期 API 能算准。
     *
     * <p><b>★ 周粒度的对齐方式</b>：桶的 key 是<b>该周周一的日期</b>（ISO 周）。
     * 如果查询起点在周中（例如周三），第一个桶从周一算起，
     * 但<b>只统计区间内的数据</b> —— 前端看到的第一桶金额会偏小，这是对齐自然周的正常结果。
     *
     * @param userId      当前登录用户
     * @param bookId      账本
     * @param type        收支类型，不传默认 EXPENSE
     * @param start       区间起点（含）
     * @param end         区间终点（<b>含</b>）
     * @param granularity 粒度：DAY / WEEK / MONTH，不传默认 DAY
     * @return 趋势数据（{@code points} 连续、补 0）
     * @throws BusinessException 粒度非法（1000）
     */
    public TrendVO trend(Long userId, Long bookId, String type,
                         LocalDate start, LocalDate end, String granularity) {
        bookService.getOwned(userId, bookId);
        String billType = normalizeType(type);
        String unit = normalizeGranularity(granularity);
        LocalDate[] range = normalizeRange(start, end);

        String key = cacheService.key(userId, bookId, "trend",
                billType, range[0], range[1], unit);
        return cacheService.getOrLoad(key, TrendVO.class,
                () -> doTrend(userId, bookId, billType, range[0], range[1], unit));
    }

    /** 实际查库的趋势逻辑（被缓存包裹）。 */
    private TrendVO doTrend(Long userId, Long bookId, String billType,
                            LocalDate from, LocalDate to, String unit) {
        // 一次按天查询，三种粒度复用
        List<DaySumRow> rows = billMapper.sumGroupByDay(
                userId, bookId, billType,
                from.atStartOfDay(), to.plusDays(1).atStartOfDay(), null);

        // 按粒度打桶（TreeMap 的 key 是 ISO 字符串，字典序即时间序）
        Map<String, BigDecimal> amountByPeriod = new TreeMap<>();
        BigDecimal total = ZERO;
        for (DaySumRow row : rows) {
            BigDecimal amount = row.getTotal() == null ? ZERO : row.getTotal();
            total = total.add(amount);
            amountByPeriod.merge(periodKey(row.getDay(), unit), amount, BigDecimal::add);
        }

        List<TrendVO.Point> points = buildAxis(from, to, unit, amountByPeriod);
        return new TrendVO(unit, from, to, total, points);
    }

    /**
     * 按粒度生成连续时间轴，并从聚合结果里取值（缺的补 0）。
     *
     * <p>三种粒度的遍历逻辑不同，但都保证"轴上每个区间恰好出现一次"。
     */
    private List<TrendVO.Point> buildAxis(LocalDate from, LocalDate to, String unit,
                                          Map<String, BigDecimal> amountByPeriod) {
        List<TrendVO.Point> points = new ArrayList<>();

        switch (unit) {
            case GRANULARITY_WEEK -> {
                // 对齐到 from 所在周的周一
                LocalDate cursor = from.with(DayOfWeek.MONDAY);
                while (!cursor.isAfter(to)) {
                    String key = cursor.toString();
                    points.add(new TrendVO.Point(key, amountByPeriod.getOrDefault(key, ZERO)));
                    cursor = cursor.plusWeeks(1);
                }
            }
            case GRANULARITY_MONTH -> {
                YearMonth cursor = YearMonth.from(from);
                YearMonth last = YearMonth.from(to);
                while (!cursor.isAfter(last)) {
                    String key = cursor.toString();
                    points.add(new TrendVO.Point(key, amountByPeriod.getOrDefault(key, ZERO)));
                    cursor = cursor.plusMonths(1);
                }
            }
            default -> {
                // DAY：从 from 逐日走到 to（含两端）
                LocalDate cursor = from;
                while (!cursor.isAfter(to)) {
                    String key = cursor.toString();
                    points.add(new TrendVO.Point(key, amountByPeriod.getOrDefault(key, ZERO)));
                    cursor = cursor.plusDays(1);
                }
            }
        }
        return points;
    }

    /**
     * 把某一天映射到它所属的区间 key。
     *
     * <p>统一用"该区间的起始日"做 key（不是"第 42 周"）——
     * 这样前端不依赖周编号约定，直接按日期排布即可。
     */
    private String periodKey(LocalDate day, String unit) {
        return switch (unit) {
            case GRANULARITY_WEEK -> day.with(DayOfWeek.MONDAY).toString();
            case GRANULARITY_MONTH -> YearMonth.from(day).toString();
            default -> day.toString();
        };
    }

    // ========================================================================
    // 内部：参数归一化
    // ========================================================================

    /** 类型只能是 EXPENSE / INCOME，不传默认 EXPENSE。 */
    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return BillService.TYPE_EXPENSE;
        }
        String upper = type.trim().toUpperCase();
        if (!BillService.TYPE_EXPENSE.equals(upper) && !BillService.TYPE_INCOME.equals(upper)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "type 只能是 EXPENSE 或 INCOME");
        }
        return upper;
    }

    /** 粒度只能是 DAY / WEEK / MONTH，不传默认 DAY。 */
    private String normalizeGranularity(String granularity) {
        if (granularity == null || granularity.isBlank()) {
            return GRANULARITY_DAY;
        }
        String upper = granularity.trim().toUpperCase();
        if (!GRANULARITY_DAY.equals(upper)
                && !GRANULARITY_WEEK.equals(upper)
                && !GRANULARITY_MONTH.equals(upper)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "granularity 只能是 DAY / WEEK / MONTH");
        }
        return upper;
    }

    /**
     * 区间归一化：不传则取<b>当前自然月</b>；起点晚于终点则交换。
     *
     * <p>★ 交换而不是报错：前端把选择器的两端拿反是常见操作，
     * 报错会让用户以为功能坏了。<b>能自动修好的参数错误不要抛给用户。</b>
     */
    private LocalDate[] normalizeRange(LocalDate start, LocalDate end) {
        YearMonth now = YearMonth.now();
        LocalDate from = start == null ? now.atDay(1) : start;
        LocalDate to = end == null ? now.atEndOfMonth() : end;
        if (from.isAfter(to)) {
            LocalDate tmp = from;
            from = to;
            to = tmp;
        }
        return new LocalDate[]{from, to};
    }

    /** 百分比：保留两位小数、四舍五入。分母为 0 时返回 0（调用方已保证 total &gt; 0）。 */
    private BigDecimal percentOf(BigDecimal amount, BigDecimal total) {
        if (total == null || total.compareTo(ZERO) == 0) {
            return ZERO;
        }
        return amount.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);
    }

    /**
     * {@code null} 转 0。
     *
     * <p>虽然 {@code BillMapper.sumAmount} 的 SQL 里有 {@code COALESCE(..., 0)}，
     * 理论上不会返回 null，但<b>多兜一层几乎没有成本</b>：
     * 一旦某个版本的 SQL 或缓存层返回了 null，"统计数字出现 null"会让前端图表直接崩，
     * 而排查这种 null 来源很费时间。
     */
    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    // ========================================================================
    // 6.5 AI 消费点评
    // ========================================================================

    /**
     * 生成 AI 消费点评。
     *
     * <p><b>★★ 本方法最关键的实现选择：数字直接复用 {@link #summary} 与
     * {@link #categoryRatio}，不重新聚合</b>
     * <pre>
     *   summary(...)        ← 收入/支出/结余/日均/最大单笔
     *   categoryRatio(...)  ← 分类占比 Top N
     *        ↓ 序列化后传给 Python
     *   Python /ai/analyze  ← 模型只负责"说人话"
     * </pre>
     * 这样做有两个好处：
     * <ol>
     *   <li><b>数字必然与图表一致</b>（验收要求）—— 因为它们本就是同一份聚合结果</li>
     *   <li><b>不额外查库</b> —— 这两个方法都走 Redis 缓存，命中时零查询</li>
     * </ol>
     * 如果为了"点评"再写第三套聚合，就会出现"点评说 1250、图表显示 1180"。
     *
     * <p><b>★ 失败一律返回 {@code null}，不抛异常</b>：
     * 点评是锦上添花。AI 超时、没配 Key、模型胡说，都只让这一块不显示，
     * <b>不能让整个统计页报错</b> —— 用户明明还能看到图表。
     *
     * @param userId 当前登录用户
     * @param bookId 账本
     * @param start  区间起点（含）；不传取当月 1 日
     * @param end    区间终点（含）；不传取当月末日
     * @return 点评；生成失败时为 {@code null}
     */
    public AiCommentVO aiComment(Long userId, Long bookId, LocalDate start, LocalDate end) {
        // ① 先看缓存：点评生成成本高（token + 十几秒），同一区间 10 分钟内不重复生成
        LocalDate[] range = normalizeRange(start, end);
        String cacheKey = cacheService.key(userId, bookId, "ai-comment", range[0], range[1]);
        AiCommentVO cached = cacheService.get(cacheKey, AiCommentVO.class);
        if (cached != null) {
            log.debug("AI 点评命中缓存：userId={}, bookId={}, key={}", userId, bookId, cacheKey);
            return cached;
        }

        // ② 复用既有聚合（★ 口径一致 + 走缓存）
        StatsSummaryVO summary = summary(userId, bookId, start, end);
        List<CategoryRatioVO> ratio = categoryRatio(userId, bookId,
                BillService.TYPE_EXPENSE, summary.start(), summary.end(), false);

        // ② 组装 Top N（已经按金额降序，直接截取）
        List<PythonAnalyzeRequest.CategoryStat> top = new ArrayList<>(
                Math.min(ratio.size(), AI_COMMENT_TOP_N));
        for (CategoryRatioVO item : ratio) {
            if (top.size() >= AI_COMMENT_TOP_N) {
                break;
            }
            // ★★ 分类名可能为 null：账单引用的分类被**删掉**时，
            //    categoryRatio 查不到名字（这是删分类的正常后果，不是 bug）。
            //    但 Python 侧 CategoryStat.name 是必填字符串 —— 直接传 null 会让
            //    整个请求 422（实测踩到：删了分类 100105 后点评一直生成不出来）。
            //    这里兜底成「已删除的分类」，保证点评永远能生成。
            String name = item.categoryName();
            if (name == null || name.isBlank()) {
                name = "已删除的分类";
            }
            top.add(new PythonAnalyzeRequest.CategoryStat(
                    name, item.amount(), item.percent()));
        }

        // ③ 带上当前生效角色的口吻（可空 —— 没角色就用中性语气）
        PersonaVO persona = personaService.getActive(userId);

        String month = YearMonth.from(summary.start()).toString();

        PythonAnalyzeRequest request = new PythonAnalyzeRequest(
                LocalDateTime.now(),
                month,
                summary.income(),
                summary.expense(),
                summary.surplus(),
                summary.dailyAverage(),
                summary.days(),
                top,
                summary.maxExpense(),
                summary.maxExpenseCategory(),
                persona == null ? null : persona.name(),
                persona == null ? null : persona.personality(),
                persona == null ? null : persona.relation(),
                persona == null ? null : persona.address(),
                persona == null ? null : persona.gender());

        // ④ 调 Python；任何失败都降级为 null
        try {
            PythonAnalyzeResponse response = aiAnalyzeClient.analyze(request);
            if (response == null || !Boolean.TRUE.equals(response.success())
                    || response.comment() == null || response.comment().isBlank()) {
                log.warn("AI 点评未成功：userId={}, bookId={}, reason={}, month={}",
                        userId, bookId,
                        response == null ? "空响应" : response.error(), month);
                return null;
            }
            log.info("AI 点评成功：userId={}, bookId={}, month={}, {} 字",
                    userId, bookId, month, response.comment().length());

            AiCommentVO vo = new AiCommentVO(month, response.comment());
            // ★ 成功才缓存（失败不缓存，下次点还能再试）
            cacheService.write(cacheKey, vo, AI_COMMENT_TTL_SECONDS);
            return vo;
        } catch (Exception e) {
            // ★ 包括超时、连接失败、反序列化失败 —— 一律降级
            log.warn("AI 点评失败，已降级为不显示：userId={}, bookId={}, err={}",
                    userId, bookId, e.getMessage());
            return null;
        }
    }
}

