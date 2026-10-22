package com.sakuya.notesatwill.entity.DTO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 解析出的<b>单条账单草稿</b>。
 *
 * <p><b>★ 它同时用于两个方向</b>：
 * <ol>
 *   <li>Java → 前端：解析完成后返回草稿，供用户在预览界面核对/修改</li>
 *   <li>前端 → Java：用户确认时把这批草稿（可能已被编辑）原样提交回来</li>
 * </ol>
 * 所以它<b>既不是"纯响应"也不是"纯请求"</b>，而是一份"双方共同编辑的表单"。
 *
 * <p><b>★ {@code accountId} 只在确认阶段有意义</b>：
 * 解析阶段它是 null（模型只给 {@code accountHint} 名字提示）；
 * 用户在前端把提示匹配成具体账户后，确认时才填上。
 * 这也是"不让模型输出 accountId"的直接后果。
 *
 * <p><b>★ {@code billTime} 在确认阶段可能仍为 null</b>：
 * 服务端在写库前会兜底为当前时间（设计 Y 的三层约定的第三层）。
 * 所以本字段<b>不能加 {@code @NotNull}</b>。
 *
 * <p><b>★ 时间格式用全局的 {@code yyyy-MM-dd HH:mm:ss}</b>：
 * 本 DTO 面向浏览器，遵循项目统一的接口格式；
 * 而 {@code entity.DTO.ai} 包里的线上契约走 ISO-8601。两者刻意分开。
 *
 * @param amount       金额，恒为正数，方向由 {@code type} 表达
 * @param type         EXPENSE / INCOME
 * @param categoryId   分类 id；可能为 null（模型没匹配到，需用户手选）
 * @param categoryName 分类名，服务端从候选集回填；前端只用于展示
 * @param accountId    资金账户 id；解析阶段为 null，确认时由前端填
 * @param accountHint  模型给的账户名提示（如"微信"），仅用于前端做预选
 * @param billTime     记账时间；可能为 null，写库前会兜底
 * @param remark       备注
 * @param confidence   0~1 的置信度，前端可据此高亮低置信度条目
 * @author sakuya
 */
public record AiBillItem(
        BigDecimal amount,
        String type,
        Long categoryId,
        String categoryName,
        Long accountId,
        String accountHint,
        LocalDateTime billTime,
        String remark,
        BigDecimal confidence) {
}
