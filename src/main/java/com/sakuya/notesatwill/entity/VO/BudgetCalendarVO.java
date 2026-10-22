package com.sakuya.notesatwill.entity.VO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 预算的日历视图：该月每一天的支出合计。
 *
 * <p><b>★★ 核心约束：{@code days} 的长度必然等于当月天数，一天不缺</b>。
 * {@code GROUP BY DATE(bill_time)} 只会返回<b>有支出</b>的日期，
 * 而日历必须 28~31 天连续 —— <b>补 0 在 Service 层做</b>，不是 SQL 的事
 * （补到几号取决于月份，闰年 2 月是 29 天）。
 *
 * <p><b>★ 为什么额外返回 {@code used}</b>：
 * 验收要求"各天之和 = 当月已用量"。把它一起返回，
 * 前端（和测试）可以直接比对 {@code sum(days[i].amount) == used}，
 * 而不必再去调一次 {@code /status} 接口。
 *
 * @param budgetId 预算 id
 * @param month    月份，格式 {@code yyyy-MM}
 * @param amount   每月额度
 * @param used     本月已用（= 各天之和）
 * @param remaining 剩余 = {@code amount - used}
 * @param days     当月每一天（<b>连续、补 0</b>，长度 = 当月天数）
 * @author sakuya
 */
public record BudgetCalendarVO(
        Long budgetId,
        String month,
        BigDecimal amount,
        BigDecimal used,
        BigDecimal remaining,
        List<Day> days) {

    /**
     * 日历中的一天。
     *
     * <p><b>★ 为什么要有 {@code hasData}</b>：
     * {@code amount = 0} 有两种含义 —— "这天没有支出" 和 "这天支出正好是 0 元"。
     * 前者前端可能想画成空白格，后者应显示 0.00。
     * 补 0 之后就分不清了，所以显式给一个标记。
     *
     * @param date    日期
     * @param amount  当天支出合计（无数据时为 0）
     * @param hasData 当天是否真的有账单
     */
    public record Day(LocalDate date, BigDecimal amount, boolean hasData) {
    }
}
