package com.sakuya.notesatwill.entity.DTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 用户确认 AI 草稿并批量落库的请求。
 *
 * <p><b>★ 为什么必须带 {@code logId}</b>：
 * 它把"这次落库"和"那次解析"绑定起来，用于
 * <ol>
 *   <li>把 {@code t_ai_parse_log} 的状态从 PENDING 改成 CONFIRMED</li>
 *   <li>把 {@code ai_log_id} 回填到账单上，将来能追溯"这笔账是哪次 AI 解析来的"</li>
 *   <li>记录用户修正差异，用于优化 Prompt</li>
 * </ol>
 * 少了它，AI 采纳率、修正率这些指标就统计不出来。
 *
 * <p><b>★ 为什么 {@code logId} 必须校验"属于当前用户且状态为 PENDING"</b>：
 * <ul>
 *   <li>属于当前用户 —— 否则可以拿别人的 logId 落库</li>
 *   <li>状态为 PENDING —— 否则同一次解析可以被重复确认，落两遍账</li>
 * </ul>
 *
 * <p><b>★ {@code bookId} 放在顶层而不是每笔各带一个</b>：
 * 一次 AI 解析对应的就是"当前选中的账本"，用户不会想让同一句话里的
 * 三笔账进入三个不同账本。放顶层既简单又符合语义。
 *
 * @param logId  解析记录 id，必须是当前用户的 PENDING 记录
 * @param bookId 账单落入的账本，必须属于当前用户
 * @param bills  用户确认后的账单列表（可能已被编辑），<b>至少一笔</b>
 * @author sakuya
 */
public record AiConfirmRequest(
        @NotNull(message = "解析记录 id 不能为空")
        Long logId,

        @NotNull(message = "账本不能为空")
        Long bookId,

        @NotEmpty(message = "至少需要确认一笔账单")
        @Size(max = 20, message = "一次最多确认 20 笔")
        List<@Valid AiBillItem> bills) {
}
