package com.sakuya.notesatwill.entity.VO;

/**
 * AI 消费点评。
 *
 * <p><b>★★ 点评里的数字与统计图表必然一致</b>：
 * 传给模型的数字全部来自 {@code StatsService} 的既有聚合
 * （与 {@code /stats/summary}、{@code /stats/category-ratio} 同源），
 * 模型被明确要求<b>只能引用、不许重算</b>。
 *
 * <p><b>★ 这个接口可能返回 {@code data: null}</b>：
 * AI 服务不可用、超时、或模型输出不合法时，本接口<b>不报错</b>，
 * 只是没有点评 —— 前端不显示这一块即可。
 * <b>点评是锦上添花，不该让整个统计页失败。</b>
 *
 * @param month   统计月份 {@code yyyy-MM}
 * @param comment 点评正文
 * @author sakuya
 */
public record AiCommentVO(
        String month,
        String comment) {
}
