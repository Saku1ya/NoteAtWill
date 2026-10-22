package com.sakuya.notesatwill.entity.DTO;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 新增 / 修改预算的请求。
 *
 * <p><b>★ {@code categoryId} 是区分「账本级」与「分类级」的唯一开关</b>：
 * <ul>
 *   <li>{@code null} → <b>账本级预算</b>：整个账本当月总支出不超过 {@code amount}</li>
 *   <li>有值 → <b>分类级预算</b>：该分类（<b>含其子分类</b>）当月支出不超过 {@code amount}</li>
 * </ul>
 * 注意这是"修改时传 null 表示改成账本级"，不是"不修改"——
 * <b>PUT 是整体替换语义</b>，与账单接口保持一致。
 *
 * <p><b>★ {@code alertThreshold} 为什么允许 0</b>：
 * 0 表示"只要花钱就预警"，虽然罕见但语义合法（严格控预算的人会用）。
 * 上限 100 是因为超过 100% 的预警没有意义（那时已经超支了，状态会变成 EXCEEDED）。
 *
 * @param bookId         所属账本，必填
 * @param categoryId     分类 id；<b>null 表示账本级预算</b>
 * @param amount         每月额度，必须大于 0
 * @param alertThreshold 预警阈值百分比（0~100），不传默认 80
 * @author sakuya
 */
public record BudgetRequest(
        @NotNull(message = "账本不能为空")
        Long bookId,

        /* 可空：null = 账本级预算，见类注释 */
        Long categoryId,

        @NotNull(message = "预算金额不能为空")
        @DecimalMin(value = "0.01", message = "预算金额必须大于 0")
        @Digits(integer = 10, fraction = 2, message = "预算金额最多两位小数")
        BigDecimal amount,

        /* 可空：不传时由 Service 补默认值 80 */
        @Min(value = 0, message = "预警阈值不能小于 0")
        @Max(value = 100, message = "预警阈值不能大于 100")
        Integer alertThreshold) {
}
