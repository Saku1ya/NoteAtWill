package com.sakuya.notesatwill.entity.VO;

import java.math.BigDecimal;

/**
 * 单个账户的对账结果（对账自测接口 {@code GET /api/dev/account-check} 的返回项）。
 *
 * <p><b>它属于「开发诊断」用途，不面向用户</b>：
 * 用户看不懂 {@code calculated} / {@code diff} / 期初余额这些内部概念，
 * 而"账目不平"对用户是恐慌信号、对开发者却只是"我代码写错了"。
 * 因此本 VO 不应出现在任何用户界面里。
 *
 * <p>对账口径：
 * <pre>
 *   calculated = init_balance + SUM(方向 IN 的金额) - SUM(方向 OUT 的金额)
 *   diff       = balance - calculated
 * </pre>
 * 一致时 {@code diff} 恒为 {@code 0.00}。
 *
 * <p><b>★ 为什么比手册给的字段多了 {@code name} 和 {@code diff}</b>：
 * <table border="1">
 *   <caption>两个附加字段的理由</caption>
 *   <tr><th>字段</th><th>理由</th></tr>
 *   <tr>
 *     <td>{@code name}</td>
 *     <td>手册的结构只有 {@code accountId}。但排查时看到 "accountId=5 不平"
 *         还得回头查是哪个账户，多一步。带上名字可以直接定位</td>
 *   </tr>
 *   <tr>
 *     <td>{@code diff}</td>
 *     <td><b>它是排查时唯一真正要看的数字。</b>
 *         只说 {@code matched=false} 无法区分"漏了一条流水"和"某笔金额写错"，
 *         有差额才能判断方向与大小，例如 {@code -20.00} 提示"少了 20"。
 *         （技术上可由 {@code balance - calculated} 导出，此处直接给出以免每次心算）</td>
 *   </tr>
 * </table>
 *
 * <p>注意：{@code balance} 与 {@code calculated} 都是<b>账户余额</b>，
 * 与账单列表里的"本月结余"是<b>完全不同的概念</b>，不要混用命名。
 *
 * @param accountId  账户 id
 * @param name       账户名（便于直接定位是哪个账户不平）
 * @param balance    {@code t_account.balance}，库里实际存的值
 * @param calculated 按资金流水算出来的值
 * @param diff       {@code balance - calculated}；为 {@code 0.00} 表示一致
 * @param matched    是否一致
 * @author sakuya
 */
public record AccountCheckVO(
        Long accountId,
        String name,
        BigDecimal balance,
        BigDecimal calculated,
        BigDecimal diff,
        boolean matched) {
}
