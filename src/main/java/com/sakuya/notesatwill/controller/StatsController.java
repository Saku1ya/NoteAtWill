package com.sakuya.notesatwill.controller;

import com.sakuya.notesatwill.common.result.Result;
import com.sakuya.notesatwill.entity.VO.AiCommentVO;
import com.sakuya.notesatwill.entity.VO.CategoryRatioVO;
import com.sakuya.notesatwill.entity.VO.StatsSummaryVO;
import com.sakuya.notesatwill.entity.VO.TrendVO;
import com.sakuya.notesatwill.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 统计接口（饼图 / 折线图的数据源）。
 *
 * <p><b>★ 区间参数用 {@code start} / {@code end}，且两端都含</b>：
 * 前端选"10 月 1 日到 10 月 31 日"时传的就是这两个日期，
 * 不存在"要不要补一天"的歧义。Service 内部转成
 * {@code [start 00:00, end+1天 00:00)} 的<b>左闭右开</b>再查库 ——
 * 这样"月末最后一秒"的账单不会漏（不用纠结 {@code 23:59:59} 还是 {@code .999}）。
 *
 * <p><b>★ 不传区间则默认当前自然月</b>，方便前端首屏直接请求。
 *
 * <p><b>★ 所有接口都需要登录</b>，且会校验 {@code bookId} 属于当前用户 ——
 * 统计能反映消费结构，越权读取等于泄露别人的生活状态。
 *
 * @author sakuya
 */
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    /**
     * 类型占比（饼图）。
     *
     * <pre>
     * GET /api/stats/category-ratio?bookId=1&amp;start=2026-10-01&amp;end=2026-10-31&amp;drillDown=true
     * →
     * [{ "categoryId": 1001, "categoryName": "餐饮", "amount": 1250.00, "percent": 62.50,
     *    "children": [ { "categoryId": 1011, "categoryName": "餐饮/午餐",
     *                    "amount": 800.00, "percent": 40.00, "children": [] } ] }]
     * </pre>
     *
     * <p><b>★ 按一级分类聚合</b>：账单存的是叶子分类 id，这里把二级的金额
     * 上卷到一级，否则饼图会被"午餐/晚餐/早餐"这类碎片撑爆。
     *
     * <p><b>★ {@code drillDown=true} 才返回二级明细</b>（默认 false）——
     * 首屏只要一级，下钻时再请求能省一半数据量。
     *
     * <p><b>★ {@code percent} 的分母是总支出</b>（不是父分类金额），
     * 所以一级百分比之和 = 100%，二级也能和一级横向比较。
     *
     * <p><b>★ 验收口径：各分类金额相加 = 总支出</b>。
     * 分类已删除的账单会作为名字为 null 的独立条目保留，不会被丢弃 ——
     * 否则"相加等于总额"就不成立了。
     *
     * @param userId    当前登录用户 id
     * @param bookId    账本 id
     * @param type      收支类型：EXPENSE（默认）/ INCOME
     * @param start     区间起点（含），不传取当月 1 日
     * @param end       区间终点（<b>含</b>），不传取当月末日
     * @param drillDown 是否返回二级明细，默认 false
     */
    @GetMapping("/category-ratio")
    public Result<List<CategoryRatioVO>> categoryRatio(
            @AuthenticationPrincipal Long userId,
            @RequestParam Long bookId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false, defaultValue = "false") boolean drillDown) {

        return Result.success(
                statsService.categoryRatio(userId, bookId, type, start, end, drillDown));
    }

    /**
     * 趋势（折线图）。
     *
     * <pre>
     * GET /api/stats/trend?bookId=1&amp;start=2026-10-01&amp;end=2026-10-31&amp;granularity=DAY
     * →
     * { "granularity": "DAY", "start": "2026-10-01", "end": "2026-10-31",
     *   "total": 2000.00,
     *   "points": [ { "period": "2026-10-01", "amount": 100.00 },
     *               { "period": "2026-10-02", "amount": 0 }, ... ] }
     * </pre>
     *
     * <p><b>★★ 时间轴连续、无数据的区间补 0</b>。
     * 不补的话折线会"跨过"空白期，把相隔五天的两笔消费画成相邻两点，
     * 视觉上完全误导。
     *
     * <p><b>★ {@code period} 的含义随粒度变化</b>：
     * <ul>
     *   <li>{@code DAY} —— 当天日期 {@code 2026-10-01}</li>
     *   <li>{@code WEEK} —— 该周<b>周一</b>的日期 {@code 2026-09-28}（ISO 周）</li>
     *   <li>{@code MONTH} —— 月份 {@code 2026-10}</li>
     * </ul>
     * 统一用"区间起始日"而不是"第 42 周"，前端不依赖周编号约定。
     *
     * <p><b>★ 三种粒度都从同一份按天数据聚合</b>，
     * 所以"日趋势加起来"必然等于"月趋势"，不会出现两个图对不上。
     *
     * @param userId      当前登录用户 id
     * @param bookId      账本 id
     * @param type        收支类型：EXPENSE（默认）/ INCOME
     * @param start       区间起点（含），不传取当月 1 日
     * @param end         区间终点（<b>含</b>），不传取当月末日
     * @param granularity 粒度：DAY（默认）/ WEEK / MONTH
     */
    @GetMapping("/trend")
    public Result<TrendVO> trend(
            @AuthenticationPrincipal Long userId,
            @RequestParam Long bookId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false) String granularity) {

        return Result.success(
                statsService.trend(userId, bookId, type, start, end, granularity));
    }

    /**
     * 区间汇总（统计页的数字卡片）。
     *
     * <pre>
     * GET /api/stats/summary?bookId=1&amp;start=2026-10-01&amp;end=2026-10-31
     * →
     * { "bookId": 1, "bookName": "日常账本",
     *   "start": "2026-10-01", "end": "2026-10-31", "days": 19,
     *   "income": 8000.00, "expense": 1250.00, "surplus": 6750.00,
     *   "dailyAverage": 65.79,
     *   "maxExpense": 380.00, "maxExpenseDate": "2026-10-05" }
     * </pre>
     *
     * <p><b>★★ {@code days} 是「参与日均计算的天数」，不等于区间总天数</b>：
     * <ul>
     *   <li>查本月（区间还没过完）→ {@code days} = 从月初到今天的天数（含两端）</li>
     *   <li>查历史月（区间已结束）→ {@code days} = 整月天数</li>
     *   <li>查未来区间 → {@code days} = 0，{@code dailyAverage} = 0</li>
     * </ul>
     * 所以前端如果要显示"本月共 N 天"，请自己算，<b>不要拿 {@code days}</b> ——
     * 这个字段的语义是"日均的分母"。
     *
     * <p><b>★ {@code maxExpense} 可能为 null</b>（区间内没有任何支出），
     * 前端显示"—"即可，不要当成 0 元。
     *
     * <p><b>★ 本接口与饼图/折线图的数字必然一致</b>：三者共用
     * {@code BillMapper.sumAmount} 的聚合口径。
     *
     * @param userId 当前登录用户 id
     * @param bookId 账本 id
     * @param start  区间起点（含），不传取当月 1 日
     * @param end    区间终点（<b>含</b>），不传取当月末日
     */
    @GetMapping("/summary")
    public Result<StatsSummaryVO> summary(
            @AuthenticationPrincipal Long userId,
            @RequestParam Long bookId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {

        return Result.success(statsService.summary(userId, bookId, start, end));
    }

    /**
     * AI 消费点评（<b>锦上添花，失败不报错</b>）。
     *
     * <pre>
     * GET /api/stats/ai-comment?bookId=1&amp;start=2026-10-01&amp;end=2026-10-31
     * →
     * { "code": 200,
     *   "data": { "month": "2026-10", "comment": "这个月你花了 1250 元，日均 65.79 元…" } }
     *
     * AI 不可用时：
     * { "code": 200, "data": null }
     * </pre>
     *
     * <p><b>★★ 点评里的数字与图表必然一致</b>：
     * 传给模型的数字直接复用 {@code /summary} 与 {@code /category-ratio} 的聚合结果，
     * 且 Prompt 明确要求"只能引用、不许重算"。
     * 所以不会出现"点评说 1250、图上显示 1180"。
     *
     * <p><b>★ 失败一律 {@code data: null}，HTTP 和业务码都是 200/成功</b>：
     * 点评依赖外部大模型，可能超时、可能没配 Key、可能生成不合法内容。
     * 这些都不该让统计页报错 —— <b>用户明明还能看到图表</b>，
     * 少一句点评不影响使用。前端按 {@code data == null} 决定不渲染这一块。
     *
     * <p><b>★ 会带上当前生效角色的口吻</b>（如果用户选了角色）。
     * 没选角色也能正常点评，只是用中性语气。
     *
     * @param userId 当前登录用户 id
     * @param bookId 账本 id
     * @param start  区间起点（含），不传取当月 1 日
     * @param end    区间终点（<b>含</b>），不传取当月末日
     */
    @GetMapping("/ai-comment")
    public Result<AiCommentVO> aiComment(
            @AuthenticationPrincipal Long userId,
            @RequestParam Long bookId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {

        return Result.success(statsService.aiComment(userId, bookId, start, end));
    }
}
