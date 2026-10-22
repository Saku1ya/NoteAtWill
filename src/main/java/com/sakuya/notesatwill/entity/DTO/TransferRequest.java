package com.sakuya.notesatwill.entity.DTO;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 转账请求（账户之间搬钱）。
 *
 * <p><b>★ 转账属于【账户模块】，不在记账模块</b>：
 * 转账是"钱从一个账户到另一个账户"，不是消费行为。
 * 所以它不产生 {@code t_bill}，只产生两条 {@code t_account_flow}
 * （转出 OUT + 转入 IN，共享同一批次号）。
 *
 * <p><b>本 DTO 刻意不包含 {@code direction}</b>：方向由服务端固定
 * （转出记 OUT、转入记 IN）。让前端传方向除了多一个出错点之外没有任何好处 ——
 * 一旦传反，余额会朝反方向走，而且对账查不出来（流水与余额一致地错）。
 *
 * <p><b>也刻意不包含 {@code transferNo}</b>：批次号必须由服务端生成。
 * 若允许前端传入，两个不同的转账就可能撞号，撤销时会一次删掉两批。
 *
 * @param fromAccountId 转出账户，必填
 * @param toAccountId   转入账户，必填；<b>不能与转出账户相同</b>（服务端校验 → 3022）
 * @param amount        转账金额，<b>必须大于 0</b>
 * @param remark        备注，可空。转账时通常记"银行卡 转 微信零钱"
 * @author sakuya
 */
public record TransferRequest(
        @NotNull(message = "转出账户不能为空")
        Long fromAccountId,

        @NotNull(message = "转入账户不能为空")
        Long toAccountId,

        @NotNull(message = "转账金额不能为空")
        @DecimalMin(value = "0.01", message = "转账金额必须大于 0")
        @Digits(integer = 10, fraction = 2, message = "金额最多两位小数")
        BigDecimal amount,

        @Size(max = 255, message = "备注不能超过 255 个字符")
        String remark) {
}
