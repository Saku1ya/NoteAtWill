/**
 * 账单 / 预算 / 统计接口。
 */
import http from './request'
import type {
  AiCommentVO,
  BillQuery,
  BillRequest,
  BillSummaryVO,
  BillVO,
  BudgetCalendarVO,
  BudgetRequest,
  BudgetStatusVO,
  BudgetVO,
  CategoryRatioVO,
  Page,
  StatsSummaryVO,
  TrendVO,
} from './types'

// ============================================================================
// 账单 /api/bills
// ============================================================================

/** 分页查询（6 种筛选条件）。 */
export function pageBills(query: BillQuery) {
  return http.get<never, Page<BillVO>>('/bills', { params: query })
}

export function getBill(id: number) {
  return http.get<never, BillVO>(`/bills/${id}`)
}

/** 本月汇总（固定查当月，不能指定区间 —— 要指定区间用 `/stats/summary`）。 */
export function billSummary(bookId: number) {
  return http.get<never, BillSummaryVO>('/bills/summary', { params: { bookId } })
}

export function createBill(payload: BillRequest) {
  return http.post<never, BillVO>('/bills', payload)
}

/**
 * 修改账单 —— <b>整体替换语义</b>。
 *
 * ★★ 必须传完整的 `BillRequest`，尤其是 `accountId`：
 *   - 传 id  = 关联该账户
 *   - 传 null = **有意解除关联**
 *   前端如果因为"这次没改账户"就省略这个字段，会被当成"要解除关联"。
 */
export function updateBill(id: number, payload: BillRequest) {
  return http.put<never, BillVO>(`/bills/${id}`, payload)
}

/** 删除账单（会回滚余额、并删除对应的对话卡片）。 */
export function deleteBill(id: number) {
  return http.delete<never, void>(`/bills/${id}`)
}

// ============================================================================
// 预算 /api/budgets
// ============================================================================

export function listBudgets(bookId?: number) {
  return http.get<never, BudgetVO[]>('/budgets', { params: { bookId } })
}

/** 新建预算。`categoryId = null` 即**账本级预算**。 */
export function createBudget(payload: BudgetRequest) {
  return http.post<never, BudgetVO>('/budgets', payload)
}

export function updateBudget(id: number, payload: BudgetRequest) {
  return http.put<never, BudgetVO>(`/budgets/${id}`, payload)
}

export function deleteBudget(id: number) {
  return http.delete<never, void>(`/budgets/${id}`)
}

/**
 * 预算执行状态（**实时聚合**，支持历史月份）。
 *
 * @param month `yyyy-MM`，不传取当月
 */
export function budgetStatus(params: { bookId?: number; month?: string }) {
  return http.get<never, BudgetStatusVO[]>('/budgets/status', { params })
}

/** 预算日历（`days` 已补 0 到整月）。 */
export function budgetCalendar(budgetId: number, month?: string) {
  return http.get<never, BudgetCalendarVO>('/budgets/calendar', {
    params: { budgetId, month },
  })
}

// ============================================================================
// 统计 /api/stats
// ============================================================================

/**
 * 区间汇总。
 *
 * ★ `days` 是"参与日均计算的天数"，**不等于区间总天数**
 *   （本月只算到今天）。前端若要显示"本月共 N 天"，自己算。
 */
export function statsSummary(params: {
  bookId: number
  start?: string
  end?: string
}) {
  return http.get<never, StatsSummaryVO>('/stats/summary', { params })
}

/**
 * 分类占比（按一级分类聚合，可下钻二级）。
 *
 * @param drillDown true 才返回 `children`
 */
export function categoryRatio(params: {
  bookId: number
  type?: 'EXPENSE' | 'INCOME'
  start?: string
  end?: string
  drillDown?: boolean
}) {
  return http.get<never, CategoryRatioVO[]>('/stats/category-ratio', { params })
}

/** 趋势（`points` 已补 0，时间轴连续）。 */
export function trend(params: {
  bookId: number
  type?: 'EXPENSE' | 'INCOME'
  start?: string
  end?: string
  granularity?: 'DAY' | 'WEEK' | 'MONTH'
}) {
  return http.get<never, TrendVO>('/stats/trend', { params })
}

/**
 * AI 消费点评。
 *
 * ★★ 返回 `null` 表示 **AI 不可用**（超时/未配置 Key），**这不是错误** ——
 *   前端不显示这一块即可，不要弹错误提示。
 * ★ 这个接口较慢（最长 15 秒），建议异步加载 + 骨架屏。
 */
export function aiComment(params: { bookId: number; start?: string; end?: string }) {
  return http.get<never, AiCommentVO | null>('/stats/ai-comment', {
    params,
    timeout: 20_000,
  })
}
