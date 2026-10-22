package com.sakuya.notesatwill.common.enums;

import java.time.LocalTime;

/**
 * 每日问候的<b>时段</b>。
 *
 * <p><b>★★ 为什么必须由服务端划分时段，而不是让大模型自己判断</b>
 * <pre>
 *   ❌ 让模型猜"现在是早上还是晚上"
 *      模型拿不到可靠时钟，也不知道用户所在时区 → 必然出错，
 *      而且这种错只在特定时段出现（比如上午的问候写成"晚上好"）很难被测出来
 *
 *   ✅ Java 按服务器时钟算好 DAWN/MORNING/… 传给 Python
 *      模型只需要按给定的时段选语气，不可能判断错
 * </pre>
 *
 * <p><b>★ 为什么分七段</b>：
 * 早上 7 点和上午 10 点说同一句"早上好"不自然；深夜 2 点更不该用白天的语气。
 * 七段足够区分，又不会碎到难以维护（与 {@code t_daily_greeting.time_bucket} 的
 * 注释保持一致）。
 *
 * <p><b>★ 区间为左闭右开</b>（{@code [from, to)}），
 * 保证任何时刻都能落到唯一一个时段，不会出现"两个时段都算"或"哪个都不算"。
 *
 * @author sakuya
 */
public enum TimeBucket {

    /** 00:00-05:00 凌晨 */
    DAWN(LocalTime.MIDNIGHT, LocalTime.of(5, 0)),

    /** 05:00-08:00 早上 */
    MORNING(LocalTime.of(5, 0), LocalTime.of(8, 0)),

    /** 08:00-11:00 上午 */
    FORENOON(LocalTime.of(8, 0), LocalTime.of(11, 0)),

    /** 11:00-13:00 中午 */
    NOON(LocalTime.of(11, 0), LocalTime.of(13, 0)),

    /** 13:00-17:00 下午 */
    AFTERNOON(LocalTime.of(13, 0), LocalTime.of(17, 0)),

    /** 17:00-19:00 傍晚 */
    EVENING(LocalTime.of(17, 0), LocalTime.of(19, 0)),

    /** 19:00-24:00 晚上 */
    NIGHT(LocalTime.of(19, 0), LocalTime.MAX);

    private final LocalTime from;
    private final LocalTime to;

    TimeBucket(LocalTime from, LocalTime to) {
        this.from = from;
        this.to = to;
    }

    /**
     * 根据时刻判断时段。
     *
     * <p><b>★ 边界规则</b>：{@code from <= t < to}。
     * 所以 05:00 属于 MORNING（不是 DAWN），19:00 属于 NIGHT（不是 EVENING）。
     * <b>左闭右开保证任意时刻恰好落在一个时段里。</b>
     *
     * @param time 时刻（用服务器时区）
     * @return 对应时段；{@code time} 为 null 时返回 {@link #MORNING}（安全默认）
     */
    public static TimeBucket of(LocalTime time) {
        if (time == null) {
            return MORNING;
        }
        for (TimeBucket bucket : values()) {
            if (!time.isBefore(bucket.from) && time.isBefore(bucket.to)) {
                return bucket;
            }
        }
        // 理论上走不到这里（NIGHT 的 to 是 LocalTime.MAX）
        return NIGHT;
    }
}
