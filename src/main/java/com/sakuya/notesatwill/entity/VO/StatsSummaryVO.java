package com.sakuya.notesatwill.entity.VO;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 区间统计汇总（饼图/折线图之外的「数字卡片」）。
 *
 * <p><b>★ 与 {@link BillSummaryVO} 的区别（两个都叫"汇总"，但语义完全不同，不要合并）</b>：
 * <table border="1">
 *   <caption>两种汇总的差异</caption>
 *   <tr><th></th><th>{@code BillSummaryVO}</th><th>本类</th></tr>
 *   <tr><td>用途</td><td>账单列表页顶部小结</td><td>统计页数字卡片</td></tr>
 *   <tr><td>区间</td><td><b>写死</b>当前自然月</td><td><b>任意</b> start/end</td></tr>
 *   <tr><td>账本</td><td>调用方指定的单个账本</td><td>单个账本（同样）</td></tr>
 *   <tr><td>额外字段</td><td>账本名</td><td><b>日均、最大单笔</b></td></tr>
 * </table>
 * 底层聚合共用 {@code BillMapper.sumAmount}，<b>但对外语义各不相同</b> ——
 * 硬合并只会让两边都长出用不上的字段。
 *
 * <p><b>★★ {@code dailyAverage} 的分母是「已过天数」而不是「区间总天数」</b>：
 * 查本月时若用总天数（如 31 天），而今天才 19 号，日均会被严重低估
 * （"这个月还剩 12 天没花"被算成了"每天都花这么少"）。
 * 详情见 {@code StatsService#summary} 的注释。
 *
 * @param bookId       账本 id
 * @param bookName     账本名
 * @param start        区间起点（含）
 * @param end          区间终点（<b>含</b>）
 * @param days         参与日均计算的天数（已过天数 / 整月天数）
 * @param income       总收入
 * @param expense      总支出
 * @param surplus      结余 = 收入 - 支出（<b>可能为负</b>）
 * @param dailyAverage 日均支出 = 支出 ÷ {@code days}（{@code days} 为 0 时为 0）
 * @param maxExpense   区间内最大单笔支出；<b>无支出时为 null</b>
 * @param maxExpenseDate 最大单笔发生的日期；与 {@code maxExpense} 同时为 null 或有值
 * @param maxExpenseCategory 最大单笔所在分类名；分类已删除时为 null
 * @author sakuya
 */
public record StatsSummaryVO(
        Long bookId,
        String bookName,
        LocalDate start,
        LocalDate end,
        long days,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal surplus,
        BigDecimal dailyAverage,
        BigDecimal maxExpense,
        LocalDate maxExpenseDate,
        String maxExpenseCategory) {
}
