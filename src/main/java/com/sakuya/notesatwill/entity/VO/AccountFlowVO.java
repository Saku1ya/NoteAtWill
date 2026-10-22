package com.sakuya.notesatwill.entity.VO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 账户资金流水列表项（{@code GET /api/accounts/{id}/flows}）。
 *
 * <p>三种来源（{@code flowType}）共用同一个结构，前端按类型决定展示细节：
 * <table border="1">
 *   <caption>三类流水各自的可用字段</caption>
 *   <tr><th>flowType</th><th>含义</th><th>专属字段</th></tr>
 *   <tr><td>{@code BILL}</td><td>记账时选了本账户</td>
 *       <td>{@code billId} 有值（可跳转到账单）</td></tr>
 *   <tr><td>{@code TRANSFER}</td><td>转账</td>
 *       <td>{@code transferNo} 有值；{@code counterpartAccountName} 是对方账户</td></tr>
 *   <tr><td>{@code ADJUST}</td><td>手动校正余额</td><td>无</td></tr>
 * </table>
 *
 * <p><b>★ 为什么要有 {@code counterpartAccountName}</b>：
 * 一条转账流水只显示"转出 100"是<b>看不懂的</b> —— 转给谁了？
 * 所以要按 {@code transferNo} 找到同批次的另一条流水，取出对方账户名。
 * 这是"批量补查"模式的第二次应用（第一次在账单列表补分类/账本/账户名），
 * <b>整页只查一次，不是 N+1</b>。
 *
 * <p><b>方向与金额的约定</b>：{@code amount} <b>恒为正数</b>，
 * 钱的进出由 {@code direction} 表达（IN 转入 / OUT 转出）。
 * 前端不要靠金额的正负判断方向。
 *
 * <p><b>★ 账户余额 vs 流水</b>：流水描述"这一次钱怎么动的"，
 * 与账户的当前余额无关。所以本 VO 里没有余额字段。
 *
 * @param id                      流水 id
 * @param flowType                来源：BILL / TRANSFER / ADJUST
 * @param direction               方向：IN 转入 / OUT 转出
 * @param amount                  变动金额，<b>恒为正数</b>
 * @param flowTime                资金变动时间；BILL 类型与账单的 {@code bill_time} 一致
 * @param remark                  备注
 * @param billId                  关联账单 id，仅 BILL 类型有值，其余为 null
 * @param transferNo              转账批次号，仅 TRANSFER 类型有值，其余为 null
 * @param counterpartAccountId    <b>转账对方</b>的账户 id，仅 TRANSFER 类型有值
 * @param counterpartAccountName  <b>转账对方</b>的账户名，仅 TRANSFER 类型有值
 * @param createTime              入库时间（与 {@code flowTime} 不同：后者是业务时间）
 * @author sakuya
 */
public record AccountFlowVO(
        Long id,
        String flowType,
        String direction,
        BigDecimal amount,
        LocalDateTime flowTime,
        String remark,
        Long billId,
        String transferNo,
        Long counterpartAccountId,
        String counterpartAccountName,
        LocalDateTime createTime) {
}
