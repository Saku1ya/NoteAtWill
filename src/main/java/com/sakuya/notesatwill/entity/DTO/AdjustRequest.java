package com.sakuya.notesatwill.entity.DTO;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 手动校正余额请求（ADJUST）。
 *
 * <p><b>★ 入参是「目标余额」而不是「调整额」</b>：
 * 用户的心智是"这个账户显示 975，但它应该是 1000"——
 * 他<b>知道正确的余额，但不知道差额</b>。差额由服务端算。
 * 若要求传调整额，用户得自己心算 1000 − 975 = 25，既麻烦又容易算错。
 *
 * <p>服务端据此换算：
 * <pre>
 *   delta = targetBalance − 当前 balance
 *   delta &gt; 0 → 记一条 IN  流水（金额 = delta）
 *   delta &lt; 0 → 记一条 OUT 流水（金额 = |delta|）
 *   delta = 0 → 什么都不做（不写流水，直接返回成功）
 * </pre>
 *
 * <p><b>★ 校正必须写流水，不能直接改 {@code balance}</b> ——
 * 因为余额是"派生数据的缓存"，它的正确性完全依赖
 * "每次变动都与一条流水同时发生"。直接改余额会让对账公式立刻失效。
 *
 * <p><b>注意本操作不会改 {@code init_balance}</b>：那个字段的定义是
 * "期初余额，建账户时填写，之后不变"。
 *
 * @param targetBalance 期望的最终余额，必填。
 *                      <b>允许为负</b> —— 与记账一致，账户余额本身允许透支
 *                      （信用卡欠款 / 先记后补都是真实需求），
 *                      所以校正到负数也是合法操作，这里不加非负约束
 * @param remark        校正原因，可空。建议填写，否则日后看到这条 ADJUST 流水会想不起为什么调
 * @author sakuya
 */
public record AdjustRequest(
        @NotNull(message = "目标余额不能为空")
        @Digits(integer = 10, fraction = 2, message = "金额最多两位小数")
        BigDecimal targetBalance,

        @Size(max = 255, message = "备注不能超过 255 个字符")
        String remark) {
}
