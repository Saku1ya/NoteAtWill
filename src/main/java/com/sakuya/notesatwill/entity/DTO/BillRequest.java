package com.sakuya.notesatwill.entity.DTO;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 新增 / 修改账单请求。
 *
 * <p><b>★ 本 DTO 的核心特征是「{@code accountId} 选填」</b>，这是本项目的产品模型决定的，
 * 不是实现偷懒：
 * <pre>
 *   不传 accountId → 只写 1 条 t_bill，纯消费记录，
 *                    t_account_flow 里 0 条，所有账户余额纹丝不动
 *   传了 accountId → 额外写 1 条 BILL 资金流水，该账户余额变动（同一事务）
 * </pre>
 * 所以 {@code accountId} <b>绝不能加 {@code @NotNull}</b> ——
 * 加了就等于宣称"每笔账都必须有账户"，把"记账"与"账户"两个独立功能重新绑死。
 *
 * <p><b>刻意排除的字段（安全设计，不是遗漏）</b>：
 * <table border="1">
 *   <caption>由服务端决定的字段</caption>
 *   <tr><th>字段</th><th>排除理由</th></tr>
 *   <tr><td>{@code userId}</td>
 *       <td>只能从 token 取。让前端传 = 允许给别人记账（越权漏洞）</td></tr>
 *   <tr><td>{@code source}</td>
 *       <td>由服务端定：手动记账固定 {@code MANUAL}，AI 确认时才是 {@code AI}。
 *           让前端传就能伪造数据来源，污染"AI 采纳率"这类统计</td></tr>
 *   <tr><td>{@code aiLogId}</td>
 *       <td>仅 AI 确认流程内部设置，外部传入无意义且可伪造</td></tr>
 *   <tr><td>{@code deleted}</td>
 *       <td>逻辑删除标记，内部状态；由数据库列默认值填 0</td></tr>
 *   <tr><td>{@code createTime} / {@code updateTime}</td>
 *       <td>由数据库 {@code DEFAULT CURRENT_TIMESTAMP} / {@code ON UPDATE} 接管 ——
 *           用数据库时钟比应用服务器时钟更可靠</td></tr>
 * </table>
 *
 * @param bookId     所属账本，必填。账本只是流水的<b>分组维度</b>，不隔离分类与账户
 * @param categoryId 分类，必填（{@code t_bill.category_id} 是 NOT NULL）。
 *                   注意：其 {@code type} 必须与下面的 {@code type} 一致，
 *                   这个跨字段约束数据库层面没有，由 Service 校验
 * @param type       收支类型：EXPENSE 支出 / INCOME 收入。
 *                   <b>只有这两种</b> —— 转账不属于记账，转账是账户模块的操作
 * @param amount     金额，恒为正数，方向由 {@code type} 表达。
 *                   用 {@code @DecimalMin("0.01")} 而非仅 {@code @NotNull}：
 *                   必须挡住 0 和负数
 * @param billTime   <b>记账时间（业务时间），必填</b>（设计 Y，2026-10-15 定案）。
 *                   <ul>
 *                     <li>前端默认填当前时间，<b>用户可改成过去</b>以支持<b>补记</b>
 *                         （例如晚上想起要记早上的账）——
 *                         这正是 {@code bill_time} 与 {@code create_time}
 *                         分成两个字段的原因</li>
 *                     <li><b>为什么是必填而不是可空</b>：手动路径的前端总会带上它，
 *                         声明"必填"才是诚实的契约。
 *                         AI 确认路径的"时间可能缺失"由它自己的
 *                         {@code AiBillItem.billTime}（可空）承担，
 *                         并在那里取 {@code now()} 归一化后再调用共用核心 ——
 *                         <b>归一化的责任在各自的入口，不在共用核心</b></li>
 *                     <li>不能晚于当前时间（留 1 分钟容差应对客户端时钟偏快），
 *                         否则抛 {@code BILL_TIME_FUTURE}</li>
 *                   </ul>
 * @param remark     备注，可空（用户经常不写）
 * @param accountId  <b>★ 选填，可为 null</b>。资金账户 ——
 *                   传了就产生一条资金流水并影响余额，不传就完全不碰账户
 * @author sakuya
 */
public record BillRequest(
        @NotNull(message = "账本不能为空")
        Long bookId,

        @NotNull(message = "分类不能为空")
        Long categoryId,

        /*
         * 用 @Pattern 收窄取值而不是只写 @NotBlank：
         * 数据库里 type 只有 EXPENSE / INCOME 两种，
         * 在参数绑定阶段就把非法值拦下，不必等 Service 再判断。
         */
        @NotBlank(message = "收支类型不能为空")
        @Pattern(regexp = "^(EXPENSE|INCOME)$", message = "收支类型只能是 EXPENSE 或 INCOME")
        String type,

        @NotNull(message = "金额不能为空")
        @DecimalMin(value = "0.01", message = "金额必须大于 0")
        @Digits(integer = 10, fraction = 2, message = "金额最多两位小数")
        BigDecimal amount,

        @NotNull(message = "记账时间不能为空")
        LocalDateTime billTime,

        @Size(max = 255, message = "备注不能超过 255 个字符")
        String remark,

        /* ★ 选填：不传则完全不触碰账户与余额 */
        Long accountId) {
}
