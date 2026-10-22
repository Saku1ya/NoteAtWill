package com.sakuya.notesatwill.entity.VO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 趋势数据（折线图）。
 *
 * <p><b>★★ 核心约束：{@code points} 的时间轴必须连续，无数据的区间补 0</b>。
 * 若只返回有数据的点，折线图会"跳过"空白期 ——
 * 比如 10-01 花了 100、10-05 花了 50，图上看像连续两天花了 100 和 50，
 * 实际中间三天是 0。<b>补 0 在 Service 层做</b>。
 *
 * <p><b>★ 为什么用 {@code DAY} 作为唯一数据源</b>：
 * 日/周/月三种粒度都从<b>同一份按天聚合的数据</b>在内存里合并得到，
 * 所以"日趋势加起来"必然等于"月趋势"，不会出现两个图对不上的情况。
 * 一个月的按天数据最多 31 行，内存开销可忽略。
 *
 * @param granularity 粒度：DAY / WEEK / MONTH
 * @param start       区间起点（含）
 * @param end         区间终点（<b>含</b>，与查询参数一致，便于前端直接显示）
 * @param total       区间总支出
 * @param points      数据点（<b>连续、补 0</b>）
 * @author sakuya
 */
public record TrendVO(
        String granularity,
        LocalDate start,
        LocalDate end,
        BigDecimal total,
        List<Point> points) {

    /**
     * 一个数据点。
     *
     * @param period 该点的标识：
     *               <ul>
     *                 <li>DAY —— {@code 2026-10-01}</li>
     *                 <li>WEEK —— 该周<b>周一</b>的日期 {@code 2026-09-28}（ISO 周，周一为始）</li>
     *                 <li>MONTH —— {@code 2026-10}</li>
     *               </ul>
     *               <b>统一用"该区间的起始日"做 key</b>，而不是"第 42 周"，
     *               这样前端不依赖周编号约定，直接按日期排布即可。
     * @param amount 该区间金额合计（无数据时为 0）
     */
    public record Point(String period, BigDecimal amount) {
    }
}
