/**
 * AI 记账（两段式）接口 `/api/ai`。
 *
 * ★★ 这是本项目最需要小心的流程：
 *   ```
 *   parse   → 返回草稿（**不落库**），存一条 PENDING 日志
 *   用户编辑 → 前端把改过的草稿提交给 confirm
 *   confirm → 批量落库（**整体事务**：任一笔失败全部回滚）
 *   ```
 *   **绝不能让 parse 直接落库** —— 模型会算错金额、编造分类、匹配错账户，
 *   必须给用户一个核对的机会。
 */
import http from './request'
import type { AiConfirmRequest, AiParseRequest, AiParseVO, BillVO } from './types'

/**
 * 解析自然语言，返回**待确认草稿**。
 *
 * ★ 返回的 `logId` 必须在 confirm 时原样带回。
 * ★ `bills[].categoryId` 可能为 null → 前端**必须**让用户选，否则 confirm 会被拒。
 * ★ `bills[].accountId` 恒为 null → 账户由用户手动选（`accountHint` 只是预选提示）。
 *
 * @param text 用户原文，≤500 字
 */
export function parseBill(text: string) {
  const payload: AiParseRequest = { text }
  // ★ 15 秒起步（后端读超时也是 15s，这里多给一点缓冲）
  return http.post<never, AiParseVO>('/ai/parse', payload, { timeout: 25_000 })
}

/**
 * 用户确认后批量落库。
 *
 * ★★ **整体事务**：任一笔非法（如分类不存在），前几笔也会回滚。
 *   前端拿到错误时，要能指出"是哪一笔"——按 `bills` 的顺序对应用户看到的列表。
 *
 * @param logId  parse 返回的日志 id
 * @param bookId 账单落入的账本
 * @param bills  用户确认后的账单（可已被编辑），**至少一笔、最多 20 笔**
 */
export function confirmBills(payload: AiConfirmRequest) {
  return http.post<never, BillVO[]>('/ai/confirm', payload)
}
