package com.sakuya.notesatwill.entity.VO;

import java.math.BigDecimal;

/**
 * 账单列表页顶部汇总（{@code GET /api/bills/summary}）。
 *
 * <p><b>口径（严格按设计文档定义，勿改）</b>：
 * <ul>
 *   <li><b>本月收入</b>：<b>当前选中账本</b>、<b>当前自然月</b>内 {@code type=INCOME} 的金额合计</li>
 *   <li><b>本月支出</b>：同上，{@code type=EXPENSE}</li>
 *   <li><b>结余</b>：{@code income - expense}</li>
 * </ul>
 *
 * <p><b>★ 只跟「账本 + 自然月」，不跟随其它筛选条件</b>（分类 / 账户 / 关键词）。
 * 因为口径本身就是这么定义的 —— 卡片展示的是"这个账本这个月花了多少"，
 * 与用户在筛选栏里选了什么无关。
 *
 * <p><b>★ 为什么结余字段叫 {@code surplus} 而不是 {@code balance}</b>：
 * {@code balance} 在本项目里已被 {@code t_account.balance}（<b>账户余额</b>）占用。
 * 两者语义完全不同 —— 一个是"本月收支差额"，一个是"卡里还有多少钱"。
 * 若同名，前端极易混用（例如把账户余额显示成月度结余）。
 *
 * @param bookId    账本 id（回显，便于前端核对统计口径）
 * @param bookName  账本名；<b>卡片上必须显示它</b>，否则用户分不清这是哪个账本的汇总
 * @param month     统计月份，格式 {@code yyyy-MM}
 * @param income    本月收入合计，无数据时为 {@code 0}
 * @param expense   本月支出合计，无数据时为 {@code 0}
 * @param surplus   结余 = {@code income - expense}；<b>可为负数</b>（本月花超了）
 * @author sakuya
 */
public record BillSummaryVO(
        Long bookId,
        String bookName,
        String month,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal surplus) {
}
