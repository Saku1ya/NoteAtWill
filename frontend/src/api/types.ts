/**
 * 后端统一响应结构。
 *
 * ★★ 关键约定：**业务错误也是 HTTP 200**，成败由 `code` 表达。
 *   - `code === 200`  → 成功，取 `data`
 *   - `code !== 200`  → 业务失败，`message` 是可直接展示给用户的中文
 *   - HTTP 401        → 未登录/登录过期（**只有这种情况才是真的非 200**）
 *
 * 所以前端不能只判 `res.status === 200`，必须再看 `code`。
 */
export interface Result<T = unknown> {
  code: number
  message: string
  data: T
}

/** 分页响应（MyBatis-Plus 的 Page）。 */
export interface Page<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

// ============================================================================
// 用户与认证
// ============================================================================

/** 用户信息（`GET /api/user/me`）。 */
export interface UserVO {
  id: number
  phone: string
  nickname: string | null
  avatar: string | null
  email: string | null
  memberLevel: number
}

/**
 * 登录/注册成功后返回的令牌。
 *
 * ★★ 踩过的坑：**这里没有 `user` 对象**！后端只返回 `{ token, expiresIn }`。
 *   需要用户信息请单独调 `GET /api/user/me`。
 *   （最初我按"登录会返回用户"写了 `token.user`，导致自动填充上下文全部失败 —— 已修正。）
 */
export interface TokenVO {
  token: string
  /** 有效期（秒）。当前实现下 token 7 天过期，没有 refresh token。 */
  expiresIn: number
}

// ============================================================================
// 账本 / 分类 / 账户
// ============================================================================

export interface BookVO {
  id: number
  name: string
  icon: string | null
  color: string | null
  sort: number
  isDefault: boolean
}

/** 分类树节点（两级）。 */
export interface CategoryTreeVO {
  id: number
  parentId: number
  name: string
  icon: string | null
  type: 'EXPENSE' | 'INCOME'
  sort: number
  /** ★ 系统预置分类不能改名、不能删，前端据此禁用按钮 */
  isPreset: boolean
  children: CategoryTreeVO[]
}

export interface AccountVO {
  id: number
  name: string
  type: 'CASH' | 'WECHAT' | 'ALIPAY' | 'BANK' | 'OTHER'
  balance: string
  initBalance: string
  icon: string | null
  sort: number
}

/** 账户流水（`GET /api/accounts/{id}/flows`）。 */
export interface AccountFlowVO {
  id: number
  flowType: 'BILL' | 'TRANSFER' | 'ADJUST'
  direction: 'IN' | 'OUT'
  amount: string
  flowTime: string
  remark: string | null
  billId: number | null
  transferNo: string | null
  counterpartAccountId: number | null
  counterpartAccountName: string | null
  createTime: string
}

// ============================================================================
// 账单
// ============================================================================

/**
 * 账单（列表/详情）。
 *
 * ★ 金额恒为正数（`amount` > 0），**方向由 `type` 表达** ——
 *   所以前端显示时要自己加正负号：`EXPENSE` 显示 `-25.00`，`INCOME` 显示 `+8000.00`。
 */
export interface BillVO {
  id: number
  bookId: number
  bookName: string
  categoryId: number
  categoryName: string | null
  type: 'EXPENSE' | 'INCOME'
  amount: string
  remark: string | null
  billTime: string
  source: 'MANUAL' | 'AI' | 'IMPORT'
  accountId: number | null
  accountName: string | null
  createTime: string
}

/** 列表页顶部汇总（`GET /api/bills/summary`，固定查当月）。 */
export interface BillSummaryVO {
  bookId: number
  bookName: string
  month: string
  income: string
  expense: string
  surplus: string
}

/** 新增/修改账单的请求体。 */
export interface BillRequest {
  bookId: number
  categoryId: number
  type: 'EXPENSE' | 'INCOME'
  amount: string | number
  billTime: string
  remark?: string | null
  /**
   * ★ 选填。传 id 表示关联该账户（会扣余额），传 null 表示不关联。
   * ★★ `PUT /api/bills/{id}` 是**整体替换**语义，编辑时必须显式传
   *    否则会被当成"要解除账户关联"。
   */
  accountId?: number | null
}

/** 账单查询条件。 */
export interface BillQuery {
  bookId: number
  type?: string | null
  categoryId?: number | null
  accountId?: number | null
  startTime?: string | null
  endTime?: string | null
  keyword?: string | null
  page?: number
  size?: number
}

// ============================================================================
// 预算
// ============================================================================

/** 预算配置（不含执行情况）。 */
export interface BudgetVO {
  id: number
  bookId: number
  bookName: string | null
  /** ★ null 表示**账本级预算**（整个账本），有值表示分类级。 */
  categoryId: number | null
  categoryName: string | null
  amount: string
  alertThreshold: number
  createTime: string
  updateTime: string
}

/** 预算执行状态（实时聚合）。 */
export interface BudgetStatusVO {
  budgetId: number
  bookId: number
  bookName: string | null
  categoryId: number | null
  categoryName: string | null
  amount: string
  used: string
  /** ★ 可能为负（超支）。 */
  remaining: string
  usedPercent: string
  status: 'NORMAL' | 'WARNING' | 'EXCEEDED'
  alertThreshold: number
  month: string
}

/** 预算日历。 */
export interface BudgetCalendarVO {
  budgetId: number
  month: string
  amount: string
  used: string
  remaining: string
  /** ★ 一定补 0 到整月，长度 = 当月天数。 */
  days: Array<{ date: string; amount: string; hasData: boolean }>
}

export interface BudgetRequest {
  bookId: number
  /** null = 账本级预算。 */
  categoryId?: number | null
  amount: string | number
  alertThreshold?: number | null
}

// ============================================================================
// 统计
// ============================================================================

export interface StatsSummaryVO {
  bookId: number
  bookName: string
  start: string
  end: string
  /** ★ 参与日均计算的天数（**不是区间总天数**——本月只算到今天）。 */
  days: number
  income: string
  expense: string
  surplus: string
  dailyAverage: string
  /** 无支出时为 null。 */
  maxExpense: string | null
  maxExpenseDate: string | null
  maxExpenseCategory: string | null
}

/** 分类占比（可下钻）。 */
export interface CategoryRatioVO {
  categoryId: number
  categoryName: string | null
  amount: string
  percent: string
  children: CategoryRatioVO[]
}

/** 趋势。 */
export interface TrendVO {
  granularity: 'DAY' | 'WEEK' | 'MONTH'
  start: string
  end: string
  total: string
  /** ★ 已补 0，时间轴连续。 */
  points: Array<{ period: string; amount: string }>
}

/** AI 消费点评。`data` 为 null 表示 AI 不可用（**不是错误**）。 */
export interface AiCommentVO {
  month: string
  comment: string
}

// ============================================================================
// AI 记账
// ============================================================================

/**
 * AI 解析出的单条账单草稿。
 *
 * ★★ 这是**待确认的草稿**，不是可直接用的账单：
 *   - `categoryId` 可能为 null（模型没匹配到分类）→ **用户必须手动选**
 *   - `accountId` 恒为 null（模型只给 `accountHint` 名字提示）→ **用户必须手动选**
 *   - `billTime` 可能为 null（后端会兜底）
 */
export interface AiBillItem {
  amount: string
  type: 'EXPENSE' | 'INCOME'
  categoryId: number | null
  categoryName: string | null
  accountId: number | null
  /** 模型猜的账户名（如"微信"），仅用于预选，不是 id。 */
  accountHint: string | null
  billTime: string | null
  remark: string | null
  /** 0~1，越低越需要用户核对。 */
  confidence: string | number | null
}

/** AI 解析结果。 */
export interface AiParseVO {
  /** ★ 确认时必须带回这个 logId。 */
  logId: number
  bills: AiBillItem[]
  /** 无法识别的原始片段。 */
  unmatched: string[]
}

export interface AiParseRequest {
  text: string
}

export interface AiConfirmRequest {
  logId: number
  bookId: number
  bills: AiBillItem[]
}

// ============================================================================
// AI 伙伴 / 对话
// ============================================================================

/** 一条对话消息。 */
export interface ChatMessageVO {
  id: number
  bookId: number | null
  role: 'USER' | 'AI' | 'SYSTEM'
  type: 'TEXT' | 'BILL_CARD' | 'STAT_CARD' | 'GREETING'
  content: string | null
  billId: number | null
  /** 卡片附加信息（真正的 JSON 对象，不是字符串）。 */
  meta: Record<string, unknown> | null
  triggerType: 'MANUAL_BILL' | 'AI_PARSE' | 'DAILY_OPEN' | null
  createTime: string
}

/** 对话历史一页（游标分页）。 */
export interface ChatMessagePageVO {
  /** ★ 按 id 倒序（最新在前）。 */
  items: ChatMessageVO[]
  /** 下一页游标；null 表示没有更多。 */
  nextCursor: number | null
  hasMore: boolean
}

/** 每日问候。 */
export interface GreetingVO {
  greetingDate: string
  timeBucket: 'DAWN' | 'MORNING' | 'FORENOON' | 'NOON' | 'AFTERNOON' | 'EVENING' | 'NIGHT'
  content: string
  /** ★ true 表示 AI 不可用、用的模板兜底。 */
  degraded: boolean
  /** ★ true 表示今天早就问候过了（前端**不要重复播报**）。 */
  alreadySent: boolean
  expense: string | null
  dailyAverage: string | null
}

/** 删除消息的结果。 */
export interface DeletionVO {
  messageId: number
  billId: number | null
  wasBillCard: boolean
  cascadeApplied: boolean
}

// ============================================================================
// 角色
// ============================================================================

/** 系统预设角色（全局只读）。 */
export interface PersonaPresetVO {
  id: number
  name: string
  avatar: string | null
  gender: 'MALE' | 'FEMALE' | 'OTHER'
  relation: string | null
  address: string | null
  personality: string | null
  sort: number
}

/** 我的角色。 */
export interface PersonaVO {
  id: number
  /** 来源预设（仅溯源，不参与读取）。 */
  presetId: number | null
  name: string
  avatar: string | null
  gender: 'MALE' | 'FEMALE' | 'OTHER'
  relation: string | null
  address: string | null
  personality: string | null
  chatReplyEnabled: boolean
  /** ★ 同一用户**至多一个** true。 */
  isActive: boolean
  createTime: string
  updateTime: string
}

export interface PersonaRequest {
  presetId?: number | null
  name?: string | null
  avatar?: string | null
  gender?: 'MALE' | 'FEMALE' | 'OTHER' | null
  relation?: string | null
  address?: string | null
  personality?: string | null
  chatReplyEnabled?: number | null
}

/** 对账结果（开发辅助）。 */
export interface AccountCheckVO {
  accountId: number
  name: string
  balance: string
  calculated: string
  diff: string
  matched: boolean
}
