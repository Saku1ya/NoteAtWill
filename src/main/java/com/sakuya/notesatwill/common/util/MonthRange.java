package com.sakuya.notesatwill.common.util;

import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * 「一个自然月」的时间区间，<b>左闭右开</b>。
 *
 * <p><b>★ 为什么要有这个类</b>：预算与统计<b>必须用完全相同的月份口径</b>，
 * 否则同一屏上会出现两个数字（"预算说已用 1200，占比图算出 1180"）。
 * 口径一旦分散在各处手写，迟早会有人把月末写成 {@code "10-31"} 而 2 月就崩。
 *
 * <p><b>★ 为什么是左闭右开而不是"当月 1 日 00:00 到月末 23:59:59"</b>：
 * <ul>
 *   <li>不需要猜月末最后一刻是 {@code 23:59:59} 还是 {@code 23:59:59.999}
 *       —— 只要漏掉 0.5 秒就会有账单两边都不落</li>
 *   <li>相邻月份天然无缝衔接：10 月的 {@code end} 就是 11 月的 {@code start}</li>
 *   <li>闰年 2 月、大小月全由 {@link YearMonth#plusMonths} 处理，不用手算天数</li>
 * </ul>
 *
 * @param start 起点（<b>含</b>），即当月 1 日 00:00:00
 * @param end   终点（<b>不含</b>），即下月 1 日 00:00:00
 * @author sakuya
 */
public record MonthRange(LocalDateTime start, LocalDateTime end) {

    /**
     * 由年月推导区间。
     *
     * @param month 目标月份
     * @return 该月的左闭右开区间
     */
    public static MonthRange of(YearMonth month) {
        return new MonthRange(
                month.atDay(1).atStartOfDay(),
                month.plusMonths(1).atDay(1).atStartOfDay());
    }
}
