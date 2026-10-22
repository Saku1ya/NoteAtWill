package com.sakuya.notesatwill.controller;

import com.sakuya.notesatwill.common.result.Result;
import com.sakuya.notesatwill.entity.Bill;
import com.sakuya.notesatwill.entity.DTO.AiConfirmRequest;
import com.sakuya.notesatwill.entity.DTO.AiParseRequest;
import com.sakuya.notesatwill.entity.VO.AiParseVO;
import com.sakuya.notesatwill.service.AiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 记账接口。
 *
 * <p><b>★ 这是一条「两段式」链路，不是单个接口</b>：
 * <pre>
 *   POST /api/ai/parse    文本 → 草稿（存 PENDING 日志，【不落库】）
 *            ↓
 *        前端预览，用户逐笔核对/修改/取消勾选
 *            ↓
 *   POST /api/ai/confirm  草稿 → 真正写入 t_bill（日志改 CONFIRMED）
 * </pre>
 *
 * <p><b>★★ 为什么必须分两步，不能一个接口搞定</b>：
 * 模型会算错金额、编造分类、匹配错账户。若解析完就落库，
 * 用户没有任何机会发现 —— 而账单错了会直接污染余额与对账。
 * 「用户确认」是这条链路上唯一的人工把关点，<b>不能省</b>。
 *
 * <p><b>★ 本类所有接口都需要登录</b>，不加 {@code permitAll}。
 * {@code userId} 一律从 token 取 —— AI 解析要读该用户的分类与账户候选，
 * 若让前端传就能探测别人的数据。
 *
 * @author sakuya
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    /**
     * 解析一段自然语言，返回待确认的账单草稿。
     *
     * <pre>
     * POST /api/ai/parse
     * { "text": "昨天中午吃饭25，打车18，超市买水果56.5" }
     * →
     * {
     *   "code": 200,
     *   "data": {
     *     "logId": 12,
     *     "bills": [
     *       { "amount": 25.00, "type": "EXPENSE", "categoryId": 1011,
     *         "categoryName": "餐饮/午餐", "accountId": null, "accountHint": null,
     *         "billTime": "2026-10-17 12:00:00", "remark": "午饭", "confidence": 0.95 },
     *       ...
     *     ],
     *     "unmatched": []
     *   }
     * }
     * </pre>
     *
     * <p><b>★ 返回的三个字段前端要这样用</b>：
     * <ul>
     *   <li>{@code logId} —— 确认时必须原样带回，否则无法关联日志</li>
     *   <li>{@code bills[].categoryId} 可能为 <b>null</b>（模型没匹配到分类），
     *       前端要提示用户手动选择，<b>不能提交 null 的 categoryId</b>
     *       （后端会拒绝，因为 {@code t_bill.category_id} 是 NOT NULL）</li>
     *   <li>{@code bills[].accountHint} 只是"模型猜的账户名"，
     *       前端用它预选账户选择器，最终由用户确认；{@code accountId} 此时为 null</li>
     * </ul>
     *
     * <p><b>降级行为</b>：Python 服务停掉时返回 5000/5001 的业务错误码，
     * HTTP 状态仍是 200 —— 前端据此提示"AI 暂时不可用，请手动记账"。
     *
     * @param userId  当前登录用户 id（来自 token）
     * @param request 用户原文
     */
    @PostMapping("/parse")
    public Result<AiParseVO> parse(@AuthenticationPrincipal Long userId,
                                   @Valid @RequestBody AiParseRequest request) {
        return Result.success(aiService.parse(userId, request));
    }

    /**
     * 用户确认草稿，批量落库。
     *
     * <pre>
     * POST /api/ai/confirm
     * {
     *   "logId": 12,
     *   "bookId": 1,
     *   "bills": [
     *     { "amount": 25.00, "type": "EXPENSE", "categoryId": 1011,
     *       "accountId": 5, "billTime": "2026-10-17 12:00:00", "remark": "午饭" },
     *     { "amount": 18.00, "type": "EXPENSE", "categoryId": 1002,
     *       "accountId": null, "billTime": "2026-10-17 12:00:00", "remark": "打车" }
     *   ]
     * }
     * </pre>
     *
     * <p><b>★ 三条关键行为</b>：
     * <ol>
     *   <li><b>整体事务</b>：N 笔 + 日志状态要么全成功，要么全失败。
     *       若第 3 笔分类非法，前 2 笔也会回滚，日志仍为 PENDING
     *       —— 否则会出现"日志说已确认、账单只落一半"</li>
     *   <li><b>不能重复确认</b>：同一条 {@code logId} 第二次确认返回 5006，
     *       避免重复记账</li>
     *   <li><b>{@code source} 与 {@code aiLogId} 由服务端写死</b>，
     *       请求体里即使传了也会被忽略</li>
     * </ol>
     *
     * <p><b>★ {@code billTime} 可以为 null</b>：服务端会兜底成当前时间
     * （三层约定的最后一道防线，因为 {@code t_bill.bill_time} 是 NOT NULL）。
     *
     * <p><b>★ {@code accountId} 是选填的</b>：不传就只是消费记录，不产生资金流水。
     * 这与手动记账的语义完全一致 —— 记账和账户是两个独立功能。
     *
     * @param userId  当前登录用户 id（来自 token）
     * @param request 确认请求（logId + bookId + 草稿列表）
     */
    @PostMapping("/confirm")
    public Result<List<Bill>> confirm(@AuthenticationPrincipal Long userId,
                                      @Valid @RequestBody AiConfirmRequest request) {
        return Result.success(aiService.confirm(userId, request));
    }
}
