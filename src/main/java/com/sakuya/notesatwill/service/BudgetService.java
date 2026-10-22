package com.sakuya.notesatwill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.common.util.MonthRange;
import com.sakuya.notesatwill.entity.Book;
import com.sakuya.notesatwill.entity.Budget;
import com.sakuya.notesatwill.entity.Category;
import com.sakuya.notesatwill.entity.DTO.BudgetRequest;
import com.sakuya.notesatwill.entity.DTO.row.CategorySumRow;
import com.sakuya.notesatwill.entity.DTO.row.DaySumRow;
import com.sakuya.notesatwill.entity.VO.BudgetCalendarVO;
import com.sakuya.notesatwill.entity.VO.BudgetStatusVO;
import com.sakuya.notesatwill.entity.VO.BudgetVO;
import com.sakuya.notesatwill.mapper.BillMapper;
import com.sakuya.notesatwill.mapper.BudgetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 预算服务：配置 CRUD + <b>实时执行状态</b> + 日历视图。
 *
 * <p><b>★★ 全类最重要的一条纪律：剩余额度与已用百分比【绝不落库】。</b>
 * <pre>
 *   used        = 实时聚合（每次现查）
 *   remaining   = amount - used      ← 推导
 *   usedPercent = used / amount * 100 ← 推导
 * </pre>
 * 把 {@code remaining} 存进数据库，就必须在"新增/修改/删除账单、改预算额度"的
 * <b>每一个</b>路径上同步更新；漏一处就出现"预算还剩 800，实际已花超"，
 * 而且<b>没有任何办法发现它错了</b>（预算表没有可对照的正确值，不像账户余额能对账）。
 *
 * <p><b>★ 分类级预算的统计范围是「子树」，不是单个分类</b>：
 * 用户记账选的是叶子分类（「餐饮/午餐」），预算却常挂在一级（「餐饮」）。
 * 只统计 {@code category_id = 餐饮} 会让预算永远是 0 使用量。
 * 详见 {@link CategoryService#subtreeIds}。
 *
 * <p><b>★ 为什么账本级与分类级要分开处理</b>：
 * 账本级只需一次总额聚合；分类级需要把"该分类 + 子分类"的金额加起来。
 * 但两者<b>共用同一个 {@link MonthRange} 区间</b>，否则同一页面会出现两个数。
 *
 * @author sakuya
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetService {

    /** 正常：已用未达预警阈值 */
    public static final String STATUS_NORMAL = "NORMAL";
    /** 预警：已用达到预警阈值，但未超过额度 */
    public static final String STATUS_WARNING = "WARNING";
    /** 超支：已用严格超过额度 */
    public static final String STATUS_EXCEEDED = "EXCEEDED";

    /** 预警阈值默认值（百分比） */
    private static final int DEFAULT_ALERT_THRESHOLD = 80;
    /** 计算百分比用的常量 */
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final BudgetMapper budgetMapper;
    private final BillMapper billMapper;
    private final BookService bookService;
    private final CategoryService categoryService;

    // ========================================================================
    // 查询配置
    // ========================================================================

    /**
     * 查预算配置列表（不含执行状态）。
     *
     * @param userId 当前登录用户
     * @param bookId 可选：只看某个账本；{@code null} 表示全部账本
     * @return 预算配置列表
     */
    public List<BudgetVO> list(Long userId, Long bookId) {
        LambdaQueryWrapper<Budget> wrapper = new LambdaQueryWrapper<Budget>()
                .eq(Budget::getUserId, userId);
        if (bookId != null) {
            // ★ 归属校验：不能查别人账本下的预算
            bookService.getOwned(userId, bookId);
            wrapper.eq(Budget::getBookId, bookId);
        }
        wrapper.orderByAsc(Budget::getBookId).orderByAsc(Budget::getId);

        return toVOs(userId, budgetMapper.selectList(wrapper));
    }

    // ========================================================================
    // 增删改
    // ========================================================================

    /**
     * 新建预算。
     *
     * @param userId  当前登录用户
     * @param request 预算信息（{@code categoryId} 为 null 表示账本级）
     * @return 新建的预算配置
     * @throws BusinessException 账本不存在（3000）/ 分类不存在（3010）/
     *                           分类不是支出类型（3015）/ 同账本同分类已有预算（4011）
     */
    @Transactional
    public BudgetVO create(Long userId, BudgetRequest request) {
        bookService.getOwned(userId, request.bookId());
        validateBudgetCategory(userId, request.categoryId());
        ensureNotDuplicated(userId, request.bookId(), request.categoryId(), null);

        Budget budget = new Budget();
        budget.setUserId(userId);
        budget.setBookId(request.bookId());
        budget.setCategoryId(request.categoryId());
        budget.setAmount(request.amount());
        budget.setAlertThreshold(alertThresholdOrDefault(request.alertThreshold()));
        budgetMapper.insert(budget);

        log.info("新建预算：userId={}, budgetId={}, bookId={}, categoryId={}, amount={}",
                userId, budget.getId(), budget.getBookId(), budget.getCategoryId(), budget.getAmount());
        return toVOs(userId, List.of(budget)).get(0);
    }

    /**
     * 修改预算（<b>整体替换</b>）。
     *
     * <p><b>★ 传 {@code categoryId = null} 表示"改成账本级预算"</b>，不是"不修改"。
     * 所以这里必须用 {@code LambdaUpdateWrapper} 显式 {@code set} ——
     * 若用 {@code updateById}，在 {@code update-strategy: not_null} 下
     * <b>null 字段会被整个跳过</b>，分类级预算永远改不回账本级
     * （这个坑在账单模块已实测踩过一次）。
     *
     * @param userId  当前登录用户
     * @param id      预算 id
     * @param request 新的预算信息
     * @return 修改后的预算配置
     * @throws BusinessException 预算不存在/不属于我（4010）/ 其它同 {@link #create}
     */
    @Transactional
    public BudgetVO update(Long userId, Long id, BudgetRequest request) {
        getOwnedBudget(userId, id);

        bookService.getOwned(userId, request.bookId());
        validateBudgetCategory(userId, request.categoryId());
        // ★ excludeId = id：排除自己，否则"不改分类只改额度"会被判定为重复
        ensureNotDuplicated(userId, request.bookId(), request.categoryId(), id);

        budgetMapper.update(null, new LambdaUpdateWrapper<Budget>()
                .eq(Budget::getId, id)
                .eq(Budget::getUserId, userId)
                .set(Budget::getBookId, request.bookId())
                // ★ 显式 set 才能写入 null（把分类级改成账本级）
                .set(Budget::getCategoryId, request.categoryId())
                .set(Budget::getAmount, request.amount())
                .set(Budget::getAlertThreshold, alertThresholdOrDefault(request.alertThreshold())));

        log.info("修改预算：userId={}, budgetId={}, bookId={}, categoryId={}, amount={}",
                userId, id, request.bookId(), request.categoryId(), request.amount());
        return toVOs(userId, List.of(budgetMapper.selectById(id))).get(0);
    }

    /**
     * 删除预算（逻辑删除）。
     *
     * <p>预算没有"执行记录"之类的下游数据，删掉不影响任何账单或余额。
     *
     * @param userId 当前登录用户
     * @param id     预算 id
     * @throws BusinessException 预算不存在/不属于我（4010）
     */
    @Transactional
    public void delete(Long userId, Long id) {
        getOwnedBudget(userId, id);
        budgetMapper.deleteById(id);
        log.info("删除预算：userId={}, budgetId={}", userId, id);
    }

    // ========================================================================
    // 执行状态（任务 5.3 + 5.4）
    // ========================================================================

    /**
     * 查预算执行状态（<b>实时聚合</b>）。
     *
     * <p><b>★ 为什么要按账本分组聚合</b>：
     * 若每条预算都单独查一次，N 条预算就是 N 次聚合 SQL。
     * 这里改成"每个账本查 2 次（总额 + 按分类）"，与预算条数无关 ——
     * <b>列表接口的查询次数不该随数据量增长</b>。
     *
     * @param userId 当前登录用户
     * @param bookId 可选：只看某个账本
     * @param month  月份 {@code yyyy-MM}；不传取当前月
     * @return 每条预算的执行状态
     * @throws BusinessException 月份格式错误（4013）
     */
    public List<BudgetStatusVO> status(Long userId, Long bookId, String month) {
        YearMonth yearMonth = parseMonth(month);
        MonthRange range = MonthRange.of(yearMonth);

        LambdaQueryWrapper<Budget> wrapper = new LambdaQueryWrapper<Budget>()
                .eq(Budget::getUserId, userId);
        if (bookId != null) {
            bookService.getOwned(userId, bookId);
            wrapper.eq(Budget::getBookId, bookId);
        }
        wrapper.orderByAsc(Budget::getId);

        List<Budget> budgets = budgetMapper.selectList(wrapper);
        if (budgets.isEmpty()) {
            return List.of();
        }

        BudgetContext ctx = loadContext(userId, budgets);

        List<BudgetStatusVO> result = new ArrayList<>(budgets.size());
        for (Budget budget : budgets) {
            BookAggregate aggregate = ctx.aggregates().computeIfAbsent(
                    budget.getBookId(), bid -> loadAggregate(userId, bid, range));
            BigDecimal used = usedOf(userId, budget, aggregate, ctx);
            result.add(toStatusVO(budget, used, yearMonth, ctx));
        }
        return result;
    }

    /**
     * 预算的日历视图：该月每天的支出合计（<b>补 0 到整月</b>）。
     *
     * <p><b>★ 为什么必须补 0</b>：{@code GROUP BY DATE(bill_time)} 只会返回
     * <b>有支出</b>的日期。不补 0 的话，前端拿到的数组长度不定（可能只有 3 个元素），
     * 没法按"第几天"定位格子 —— 日历会错位。
     *
     * @param userId   当前登录用户
     * @param budgetId 预算 id
     * @param month    月份 {@code yyyy-MM}；不传取当前月
     * @return 日历数据（{@code days} 长度 = 当月天数）
     * @throws BusinessException 预算不存在/不属于我（4010）/ 月份格式错误（4013）
     */
    public BudgetCalendarVO calendar(Long userId, Long budgetId, String month) {
        Budget budget = getOwnedBudget(userId, budgetId);
        YearMonth yearMonth = parseMonth(month);
        MonthRange range = MonthRange.of(yearMonth);

        // 账本级预算不过滤分类（scope = null）；分类级预算按子树过滤
        Set<Long> scope = budget.getCategoryId() == null
                ? null
                : categoryService.subtreeIds(userId, budget.getCategoryId());

        List<DaySumRow> rows = billMapper.sumGroupByDay(
                userId, budget.getBookId(), BillService.TYPE_EXPENSE,
                range.start(), range.end(), scope);

        Map<LocalDate, BigDecimal> amountByDay = new HashMap<>(rows.size());
        for (DaySumRow row : rows) {
            amountByDay.put(row.getDay(), row.getTotal() == null ? ZERO : row.getTotal());
        }

        // ★ 从当月 1 日走到月末，缺的日期补 0
        List<BudgetCalendarVO.Day> days = new ArrayList<>(yearMonth.lengthOfMonth());
        BigDecimal used = ZERO;
        for (LocalDate day = yearMonth.atDay(1);
             !day.isAfter(yearMonth.atEndOfMonth());
             day = day.plusDays(1)) {

            BigDecimal amount = amountByDay.get(day);
            boolean hasData = amount != null;
            BigDecimal value = hasData ? amount : ZERO;
            days.add(new BudgetCalendarVO.Day(day, value, hasData));
            used = used.add(value);
        }

        log.debug("预算日历：userId={}, budgetId={}, month={}, 有数据天数={}, 合计={}",
                userId, budgetId, yearMonth, amountByDay.size(), used);

        return new BudgetCalendarVO(
                budget.getId(),
                yearMonth.toString(),
                budget.getAmount(),
                used,
                budget.getAmount().subtract(used),
                days);
    }

    // ========================================================================
    // 内部：归属与校验
    // ========================================================================

    /** 取属于我的预算，否则抛 4010（"不存在"与"不是我的"合并，防 id 枚举）。 */
    private Budget getOwnedBudget(Long userId, Long id) {
        Budget budget = budgetMapper.selectById(id);
        if (budget == null || !Objects.equals(budget.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.BUDGET_NOT_FOUND);
        }
        return budget;
    }

    /**
     * 校验预算的分类：可空；有值时必须可见且是<b>支出</b>类型。
     *
     * <p><b>★ 为什么必须限制为支出分类</b>：预算衡量的是"花多少"。
     * 给一个收入分类设预算，已用量会恒为 0（因为收入不进 EXPENSE 聚合），
     * 用户会看到一条永远"剩余 100%"的预算 —— 这是无意义的配置，不如直接拒绝。
     */
    private void validateBudgetCategory(Long userId, Long categoryId) {
        if (categoryId == null) {
            return;   // 账本级预算，无需校验分类
        }
        Category category = categoryService.getVisible(userId, categoryId);
        if (!BillService.TYPE_EXPENSE.equals(category.getType())) {
            throw new BusinessException(ErrorCode.CATEGORY_TYPE_MISMATCH,
                    "预算只能针对支出分类");
        }
    }

    /**
     * 同账本同分类只允许一条预算。
     *
     * <p><b>★★ 为什么必须在 Service 层做，而不是靠数据库唯一索引</b>：
     * <ul>
     *   <li>账本级预算的 {@code category_id} 是 <b>NULL</b>，
     *       而 MySQL 的 UNIQUE 索引<b>允许多个 NULL</b> —— 唯一索引对账本级完全无效</li>
     *   <li>即使为分类级加唯一索引，也会被逻辑删除干扰：
     *       删掉一条预算后（{@code deleted=1}）该行仍占着唯一键</li>
     * </ul>
     *
     * <p><b>★ 注意 {@code isNull()} 与 {@code eq(col, null)} 的区别</b>：
     * 后者生成 {@code category_id = NULL}，在 SQL 里恒为 UNKNOWN，<b>查不到任何行</b>，
     * 于是"账本级去重"会静默失效、允许插入无限多条重复预算。
     *
     * @param excludeId 修改时传自己的 id，避免"只改额度"被判重复
     */
    private void ensureNotDuplicated(Long userId, Long bookId, Long categoryId, Long excludeId) {
        LambdaQueryWrapper<Budget> wrapper = new LambdaQueryWrapper<Budget>()
                .eq(Budget::getUserId, userId)
                .eq(Budget::getBookId, bookId);

        if (categoryId == null) {
            wrapper.isNull(Budget::getCategoryId);   // ★ 不能写成 eq(..., null)
        } else {
            wrapper.eq(Budget::getCategoryId, categoryId);
        }
        if (excludeId != null) {
            wrapper.ne(Budget::getId, excludeId);
        }

        Long count = budgetMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.BUDGET_ALREADY_EXISTS);
        }
    }

    // ========================================================================
    // 内部：聚合
    // ========================================================================

    /**
     * 某个账本在某月的聚合快照。
     *
     * <p>一个账本只查 2 次 SQL，供该账本下所有预算复用。
     *
     * @param total      账本当月支出总额（账本级预算用）
     * @param byCategory 分类 id → 当月支出（分类级预算用）
     */
    private record BookAggregate(BigDecimal total, Map<Long, BigDecimal> byCategory) {
    }

    /** 预算列表的公共上下文：账本名、分类名、聚合缓存、子树缓存。 */
    private record BudgetContext(
            Map<Long, String> bookNames,
            Map<Long, String> categoryNames,
            Map<Long, BookAggregate> aggregates,
            Map<Long, Set<Long>> subtrees) {
    }

    /**
     * 一次性把状态接口需要的名字与缓存准备好。
     *
     * <p>账本名 1 次查询、分类名 1 次查询 —— <b>与预算条数无关</b>。
     */
    private BudgetContext loadContext(Long userId, List<Budget> budgets) {
        Map<Long, String> bookNames = new HashMap<>();
        for (Book book : bookService.list(userId)) {
            bookNames.put(book.getId(), book.getName());
        }

        Set<Long> categoryIds = budgets.stream()
                .map(Budget::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> categoryNames = categoryService.nameMap(categoryIds);

        return new BudgetContext(bookNames, categoryNames, new HashMap<>(), new HashMap<>());
    }

    /** 查某账本某月的聚合（总额 + 按分类）。 */
    private BookAggregate loadAggregate(Long userId, Long bookId, MonthRange range) {
        BigDecimal total = billMapper.sumAmount(
                userId, bookId, BillService.TYPE_EXPENSE, range.start(), range.end());

        List<CategorySumRow> rows = billMapper.sumGroupByCategory(
                userId, bookId, BillService.TYPE_EXPENSE, range.start(), range.end());

        Map<Long, BigDecimal> byCategory = new HashMap<>(rows.size());
        for (CategorySumRow row : rows) {
            byCategory.put(row.getCategoryId(), row.getTotal() == null ? ZERO : row.getTotal());
        }
        return new BookAggregate(total == null ? ZERO : total, byCategory);
    }

    /**
     * 算某条预算的已用量。
     *
     * <p>账本级：直接用账本总额。分类级：把"该分类 + 子分类"的金额加起来。
     * 子树缓存在 {@link BudgetContext} 里，同一个分类只查一次。
     *
     * @param ctx 预算列表的公共上下文（含子树缓存）—— <b>显式传参，不用 ThreadLocal</b>：
     *            隐式上下文在异步/线程池下会串数据，且忘记 remove 就会泄漏
     */
    private BigDecimal usedOf(Long userId, Budget budget, BookAggregate aggregate, BudgetContext ctx) {
        if (budget.getCategoryId() == null) {
            return aggregate.total();
        }
        Set<Long> scope = ctx.subtrees().computeIfAbsent(
                budget.getCategoryId(),
                cid -> categoryService.subtreeIds(userId, cid));

        return aggregate.byCategory().entrySet().stream()
                .filter(entry -> scope.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .reduce(ZERO, BigDecimal::add);
    }

    // ========================================================================
    // 内部：状态判定
    // ========================================================================

    /**
     * 组装执行状态 VO。
     *
     * <p><b>★ 百分比保留两位小数（HALF_UP）</b>：财务习惯用四舍五入而不是银行家舍入。
     * {@code amount} 恒大于 0（有校验），所以不会出现除零。
     */
    private BudgetStatusVO toStatusVO(Budget budget, BigDecimal used,
                                      YearMonth month, BudgetContext ctx) {
        BigDecimal amount = budget.getAmount();
        BigDecimal remaining = amount.subtract(used);
        BigDecimal usedPercent = used.multiply(HUNDRED)
                .divide(amount, 2, RoundingMode.HALF_UP);

        return new BudgetStatusVO(
                budget.getId(),
                budget.getBookId(),
                ctx.bookNames().get(budget.getBookId()),
                budget.getCategoryId(),
                budget.getCategoryId() == null ? null : ctx.categoryNames().get(budget.getCategoryId()),
                amount,
                used,
                remaining,
                usedPercent,
                decideStatus(used, amount, usedPercent, budget.getAlertThreshold()),
                budget.getAlertThreshold(),
                month.toString());
    }

    /**
     * 判定预警状态。
     *
     * <p><b>★ 边界口径（容易搞错）</b>：
     * <ul>
     *   <li>已用<b>严格大于</b>额度 → {@code EXCEEDED}。正好用满（{@code used == amount}）
     *       <b>不算超支</b>，因为"刚好花完预算"并没有超出计划</li>
     *   <li>未超支，且百分比达到阈值 → {@code WARNING}</li>
     *   <li>其余 → {@code NORMAL}</li>
     * </ul>
     * 所以阈值配 100 的用户，只有真正超支才会收到 EXCEEDED，用满当天是 WARNING。
     */
    private String decideStatus(BigDecimal used, BigDecimal amount,
                                BigDecimal usedPercent, Integer alertThreshold) {
        if (used.compareTo(amount) > 0) {
            return STATUS_EXCEEDED;
        }
        int threshold = alertThresholdOrDefault(alertThreshold);
        if (usedPercent.compareTo(BigDecimal.valueOf(threshold)) >= 0) {
            return STATUS_WARNING;
        }
        return STATUS_NORMAL;
    }

    // ========================================================================
    // 内部：杂项
    // ========================================================================

    /** 解析 {@code yyyy-MM}；{@code null}/空白取当前月，格式错误抛 4013。 */
    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.BUDGET_MONTH_INVALID);
        }
    }

    private int alertThresholdOrDefault(Integer value) {
        return value == null ? DEFAULT_ALERT_THRESHOLD : value;
    }

    /** 批量组装预算配置 VO（账本名与分类名各只查一次）。 */
    private List<BudgetVO> toVOs(Long userId, List<Budget> budgets) {
        if (budgets.isEmpty()) {
            return List.of();
        }

        Map<Long, String> bookNames = new HashMap<>();
        for (Book book : bookService.list(userId)) {
            bookNames.put(book.getId(), book.getName());
        }

        Set<Long> categoryIds = budgets.stream()
                .map(Budget::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> categoryNames = categoryService.nameMap(categoryIds);

        List<BudgetVO> result = new ArrayList<>(budgets.size());
        for (Budget budget : budgets) {
            result.add(new BudgetVO(
                    budget.getId(),
                    budget.getBookId(),
                    bookNames.get(budget.getBookId()),
                    budget.getCategoryId(),
                    budget.getCategoryId() == null ? null : categoryNames.get(budget.getCategoryId()),
                    budget.getAmount(),
                    budget.getAlertThreshold(),
                    budget.getCreateTime(),
                    budget.getUpdateTime()));
        }
        return result;
    }
}
