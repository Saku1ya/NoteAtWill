/**
 * 账本 / 分类 / 账户接口。
 */
import http from './request'
import type {
  AccountCheckVO,
  AccountFlowVO,
  AccountVO,
  BookVO,
  CategoryTreeVO,
} from './types'

// ============================================================================
// 账本 /api/books
// ============================================================================

export function listBooks() {
  return http.get<never, BookVO[]>('/books')
}

export function createBook(payload: {
  name: string
  icon?: string | null
  color?: string | null
  sort?: number | null
}) {
  return http.post<never, BookVO>('/books', payload)
}

export function updateBook(
  id: number,
  payload: { name: string; icon?: string | null; color?: string | null; sort?: number | null },
) {
  return http.put<never, BookVO>(`/books/${id}`, payload)
}

export function deleteBook(id: number) {
  return http.delete<never, void>(`/books/${id}`)
}

// ============================================================================
// 分类 /api/categories
// ============================================================================

/** 查分类树。`type` 不传返回两棵树？—— 实际后端按 type 过滤，必须传。 */
export function treeCategories(type: 'EXPENSE' | 'INCOME') {
  return http.get<never, CategoryTreeVO[]>('/categories', { params: { type } })
}

export function createCategory(payload: {
  parentId: number
  name: string
  icon?: string | null
  sort?: number | null
  type: 'EXPENSE' | 'INCOME'
}) {
  return http.post<never, CategoryTreeVO>('/categories', payload)
}

export function updateCategory(
  id: number,
  payload: { name: string; icon?: string | null; sort?: number | null; type: 'EXPENSE' | 'INCOME' },
) {
  return http.put<never, CategoryTreeVO>(`/categories/${id}`, payload)
}

export function deleteCategory(id: number) {
  return http.delete<never, void>(`/categories/${id}`)
}

// ============================================================================
// 账户 /api/accounts
// ============================================================================

export function listAccounts() {
  return http.get<never, AccountVO[]>('/accounts')
}

export function createAccount(payload: {
  name: string
  type: AccountVO['type']
  initBalance?: string | number | null
  icon?: string | null
  sort?: number | null
}) {
  return http.post<never, AccountVO>('/accounts', payload)
}

export function updateAccount(
  id: number,
  payload: {
    name: string
    type: AccountVO['type']
    icon?: string | null
    sort?: number | null
  },
) {
  return http.put<never, AccountVO>(`/accounts/${id}`, payload)
}

export function deleteAccount(id: number) {
  return http.delete<never, void>(`/accounts/${id}`)
}

/** 转账：返回批次号（撤销时要用）。 */
export function transfer(payload: {
  fromAccountId: number
  toAccountId: number
  amount: string | number
  remark?: string | null
}) {
  return http.post<never, string>('/accounts/transfer', payload)
}

/** 撤销转账（按批次号成对作废两条流水）。 */
export function cancelTransfer(transferNo: string) {
  return http.delete<never, void>(`/accounts/transfer/${transferNo}`)
}

/** 手动校正余额到指定值（**传的是目标余额，不是差额**）。 */
export function adjustAccount(
  id: number,
  payload: { targetBalance: string | number; remark?: string | null },
) {
  return http.post<never, void>(`/accounts/${id}/adjust`, payload)
}

/** 账户流水（分页）。 */
export function listAccountFlows(id: number, page = 1, size = 20, flowType?: string) {
  return http.get<never, AccountFlowVO[]>(`/accounts/${id}/flows`, {
    params: { page, size, flowType },
  })
}

// ============================================================================
// 开发辅助 /api/dev（仅 dev profile 生效）
// ============================================================================

/** 对账自检：检查所有账户的"余额 == 期初 + ΣIN − ΣOUT"。 */
export function accountCheck() {
  return http.get<never, AccountCheckVO[]>('/dev/account-check')
}
