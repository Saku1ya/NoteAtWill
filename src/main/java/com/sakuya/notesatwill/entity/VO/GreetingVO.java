package com.sakuya.notesatwill.entity.VO;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 每日问候（当天首次打开页面时的响应）。
 *
 * <p><b>★★ {@code alreadySent} 的含义：这次请求是不是"当天第一次"</b>
 * <pre>
 *   false → 刚生成（或刚好命中并发窗口），前端应【插入】这条问候并播报
 *   true  → 今天已经问候过了，返回的是【已存在的那一条】
 *           前端应把它当成历史消息渲染，<b>不要重复播报</b>
 * </pre>
 * 靠数据库唯一索引 {@code uk_user_date(user_id, greet_date)} 保证"一天只有一条"，
 * 而不是靠"先查再插"（并发下必漏）。
 *
 * <p><b>★ {@code degraded} 表示 AI 不可用、用了模板兜底</b>：
 * 前端可以据此决定是否显示"AI 暂时不可用"的轻提示；
 * <b>但内容照常展示</b> —— 模板里也有真实数字。
 *
 * @param greetingDate 问候日期
 * @param timeBucket   时段（DAWN/MORNING/…/NIGHT）
 * @param content      问候语
 * @param degraded     <b>是否降级为模板</b>（AI 不可用时为 true）
 * @param alreadySent  今天是否早就有问候了（本次请求复用了它）
 * @param expense      本月支出（模板兜底时前端可直接展示）
 * @param dailyAverage 日均支出
 * @author sakuya
 */
public record GreetingVO(
        LocalDate greetingDate,
        String timeBucket,
        String content,
        boolean degraded,
        boolean alreadySent,
        BigDecimal expense,
        BigDecimal dailyAverage) {
}
