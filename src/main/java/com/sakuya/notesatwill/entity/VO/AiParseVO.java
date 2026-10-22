package com.sakuya.notesatwill.entity.VO;

import com.sakuya.notesatwill.entity.DTO.AiBillItem;

import java.util.List;

/**
 * AI 解析结果（返回给前端的草稿）。
 *
 * <p><b>★ 为什么要返回 {@code logId}</b>：
 * 前端确认时必须把它带回来（{@code POST /api/ai/confirm}），
 * 服务端靠它把"这次落库"和"那次解析"关联起来 ——
 * 更新日志状态、回填 {@code ai_log_id}、统计用户修正率都依赖它。
 *
 * <p><b>★ 这里返回的是「草稿」，不能直接落库</b>：
 * <ul>
 *   <li>{@code categoryId} 可能为 null（模型没匹配到，需用户手选）</li>
 *   <li>{@code accountId} 为 null（模型只给名字提示，账户由用户确认）</li>
 *   <li>{@code billTime} 可能为 null（写库前兜底）</li>
 * </ul>
 * 用户在前端核对/修改后调 {@code /api/ai/confirm} 才真正写进数据库。
 *
 * @param logId     解析记录 id，确认时必须带回
 * @param bills     解析出的账单草稿
 * @param unmatched 无法归类的原始片段，前端可提示"这段没识别出来，请手动记账"
 * @author sakuya
 */
public record AiParseVO(
        Long logId,
        List<AiBillItem> bills,
        List<String> unmatched) {
}
