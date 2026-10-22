<script setup lang="ts">
/**
 * 接口联调页 —— 在写正式 UI 之前，用来验证"前后端真的能通"。
 *
 * 设计目标（刻意做得极简）：
 *   1. 覆盖全部 54 个端点，每个都能点一次
 *   2. 能看到**原始请求与响应**（不美化，因为要看真实结构）
 *   3. 自动维护上下文（登录后 token、账本 id、账户 id 自动填进后续请求）
 *   4. 结果分「成功 / 业务失败 / 网络异常」三色，一眼看出问题类型
 *
 * ★ 这个页面**不会**进入正式产品，只用于开发期联调。
 */
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as authApi from './api/auth'
import * as userApi from './api/user'
import * as baseApi from './api/base'
import * as billApi from './api/bill'
import * as aiApi from './api/ai'
import * as chatApi from './api/chat'
import * as personaApi from './api/persona'
import { BusinessError, clearToken, getToken, setToken } from './api/request'
import type { AiParseVO } from './api/types'

// ============================================================================
// 状态
// ============================================================================

/** 联调过程中的上下文（自动填充，省得每次手输 id）。 */
const ctx = reactive({
  phone: '13900000001',
  password: 'pass123456',
  code: '',
  token: getToken() ?? '',
  userId: 0,
  bookId: 0,
  accountId: 0,
  accountId2: 0,
  categoryId: 0,
  billId: 0,
  budgetId: 0,
  personaId: 0,
  transferNo: '',
  logId: 0,
  messageId: 0,
})

interface LogEntry {
  time: string
  name: string
  ok: boolean
  /** 'success' | 'business' | 'network' */
  kind: 'success' | 'business' | 'network'
  detail: string
}

const logs = ref<LogEntry[]>([])
const running = ref('')
const onlyFailures = ref(false)

const shownLogs = computed(() =>
  onlyFailures.value ? logs.value.filter((l) => !l.ok) : logs.value,
)

function push(name: string, kind: LogEntry['kind'], detail: string) {
  const now = new Date()
  const time = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`
  logs.value.unshift({ time, name, ok: kind === 'success', kind, detail })
  if (logs.value.length > 200) logs.value.pop()
}

/**
 * 统一执行一次接口调用，并把结果记进日志。
 *
 * ★ 三种结果分开记录，因为它们的处理方式完全不同：
 *   - success  ：HTTP 200 + code 200
 *   - business ：HTTP 200 + code ≠ 200（**业务规则拒绝**，如"分类不存在"）
 *   - network  ：真的连不上 / 超时 / 401
 */
async function run<T = unknown>(name: string, fn: () => Promise<T>): Promise<T | undefined> {
  running.value = name
  try {
    const data = await fn()
    push(name, 'success', JSON.stringify(data, null, 2))
    ElMessage.success(`${name} 成功`)
    return data
  } catch (e) {
    if (e instanceof BusinessError && e.code !== 401 && e.code !== -1) {
      // 业务错误：HTTP 200 但 code≠200，**这是正常的业务路径**，不算 bug
      push(name, 'business', `code=${e.code}\n${e.message}`)
      ElMessage.warning(`${name}：${e.message}`)
    } else {
      push(name, 'network', String(e))
      ElMessage.error(`${name} 失败`)
    }
    return undefined
  } finally {
    running.value = ''
  }
}

// ============================================================================
// 认证
// ============================================================================

async function doSendCode() {
  await run('send-code 发送验证码', async () => {
    await authApi.sendCode(ctx.phone)
    // ★ 开发环境后端把验证码写进日志，这里只是提示去日志里找
    ElMessage.info('验证码已发送（开发环境请到后端日志里找 6 位数字，或看 Redis）')
  })
}

async function doRegister() {
  const r = await run<{ token?: string; expiresIn?: number }>('register 注册', () =>
    authApi.register({
      phone: ctx.phone,
      code: ctx.code,
      password: ctx.password,
      nickname: '联调用户',
    }),
  )
  await handleLoginResult(r)
}

/**
 * 登录成功后的公共处理。
 *
 * ★★ 踩过的坑：登录接口返回的是 `{ token, expiresIn }`，
 *   **没有 `user` 对象**！曾经写成 `ctx.userId = r.user.id`，
 *   `r.user` 是 undefined → 抛 TypeError → 后面的 `loadBasics()` 不执行
 *   → 表现就是"只有 token 被填上，其它上下文全是空"。
 *
 *   正确做法：**只取 token**，用户信息统一从 `GET /api/user/me` 拿
 *   （`loadBasics` 里已经这么做了）。
 */
async function handleLoginResult(r: { token?: string; expiresIn?: number } | undefined) {
  if (!r?.token) {
    return
  }
  ctx.token = r.token
  setToken(r.token)
  await loadBasics()
}

async function doLoginPassword() {
  const r = await run<{ token?: string; expiresIn?: number }>('login/password 密码登录', () =>
    authApi.loginByPassword(ctx.phone, ctx.password),
  )
  await handleLoginResult(r)
}

async function doLoginCode() {
  const r = await run<{ token?: string; expiresIn?: number }>('login/code 验证码登录', () =>
    authApi.loginByCode(ctx.phone, ctx.code),
  )
  await handleLoginResult(r)
}

async function doLogout() {
  await run('logout 登出', () => authApi.logout())
  ctx.token = ''
  clearToken()
}

// ============================================================================
// 自动填充上下文
// ============================================================================

/** 登录成功后自动拉取 id，避免每次手输。 */
async function loadBasics() {
  const me = await run<{ id: number }>('user/me 当前用户', () => userApi.getMe())
  if (me?.id) ctx.userId = me.id

  const books = await run<Array<{ id: number }>>('books 账本列表', () => baseApi.listBooks())
  if (books?.length) ctx.bookId = books[0].id

  const accounts = await run<Array<{ id: number }>>('accounts 账户列表', () =>
    baseApi.listAccounts(),
  )
  if (accounts?.length) {
    ctx.accountId = accounts[0].id
    ctx.accountId2 = accounts[accounts.length - 1].id
  }

  const tree = await run<Array<{ id: number }>>('categories 分类树', () =>
    baseApi.treeCategories('EXPENSE'),
  )
  if (tree?.length) ctx.categoryId = tree[0].id
}

/** 一键跑完"登录 → 取上下文"这套前置动作。 */
async function bootstrap() {
  await doLoginPassword()
}

// ============================================================================
// 账本 / 分类 / 账户
// ============================================================================

async function createBook() {
  await run('POST books 建账本', () => baseApi.createBook({ name: `联调账本${Date.now() % 1000}` }))
}

async function updateBook() {
  await run('PUT books/{id} 改账本', () =>
    baseApi.updateBook(ctx.bookId, { name: `改名${Date.now() % 1000}` }),
  )
}

async function deleteBook() {
  const books = await run<Array<{ id: number }>>('books 列表（取非默认）', () => baseApi.listBooks())
  const target = books?.find((b) => b.id !== ctx.bookId)
  if (!target) {
    ElMessage.info('没有可删的账本（默认账本不能删）')
    return
  }
  await run('DELETE books/{id} 删账本', () => baseApi.deleteBook(target.id))
}

async function createCategory() {
  const r = await run<{ id: number }>('POST categories 建分类', () =>
    baseApi.createCategory({
      parentId: 0,
      name: `联调分类${Date.now() % 1000}`,
      type: 'EXPENSE',
    }),
  )
  if (r?.id) ctx.categoryId = r.id
}

async function updateCategory() {
  await run('PUT categories/{id} 改分类', () =>
    baseApi.updateCategory(ctx.categoryId, {
      name: `改名${Date.now() % 1000}`,
      type: 'EXPENSE',
    }),
  )
}

async function deleteCategory() {
  await run('DELETE categories/{id} 删分类', () => baseApi.deleteCategory(ctx.categoryId))
}

async function createAccount() {
  const r = await run<{ id: number }>('POST accounts 建账户', () =>
    baseApi.createAccount({
      name: `联调卡${Date.now() % 1000}`,
      type: 'BANK',
      initBalance: 1000,
    }),
  )
  if (r?.id) ctx.accountId2 = r.id
}

async function updateAccount() {
  await run('PUT accounts/{id} 改账户', () =>
    baseApi.updateAccount(ctx.accountId2, { name: `改名${Date.now() % 1000}`, type: 'BANK' }),
  )
}

async function doTransfer() {
  const r = await run<string>('POST accounts/transfer 转账', () =>
    baseApi.transfer({
      fromAccountId: ctx.accountId2,
      toAccountId: ctx.accountId,
      amount: 100,
      remark: '联调转账',
    }),
  )
  if (typeof r === 'string') ctx.transferNo = r
}

async function cancelTransfer() {
  await run('DELETE accounts/transfer/{no} 撤销转账', () =>
    baseApi.cancelTransfer(ctx.transferNo),
  )
}

async function doAdjust() {
  await run('POST accounts/{id}/adjust 校正余额', () =>
    baseApi.adjustAccount(ctx.accountId2, { targetBalance: 888.88, remark: '联调校正' }),
  )
}

async function listFlows() {
  await run('GET accounts/{id}/flows 账户流水', () => baseApi.listAccountFlows(ctx.accountId2))
}

async function deleteAccount() {
  await run('DELETE accounts/{id} 删账户', () => baseApi.deleteAccount(ctx.accountId2))
}

// ============================================================================
// 账单
// ============================================================================

function nowStr(offsetMinutes = -1): string {
  const d = new Date(Date.now() + offsetMinutes * 60_000)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

async function createBill() {
  const r = await run<{ id: number }>('POST bills 记账', () =>
    billApi.createBill({
      bookId: ctx.bookId,
      categoryId: ctx.categoryId,
      type: 'EXPENSE',
      // ★ 用"1 分钟前"而不是 12:00 —— 上午跑测试时 12:00 是未来，会被拒
      amount: 25,
      billTime: nowStr(-1),
      remark: '联调午饭',
      accountId: ctx.accountId,
    }),
  )
  if (r?.id) ctx.billId = r.id
}

async function pageBills() {
  const r = await run<{ records: Array<{ id: number }> }>('GET bills 分页查询', () =>
    billApi.pageBills({ bookId: ctx.bookId, page: 1, size: 10 }),
  )
  if (r?.records?.length) ctx.billId = r.records[0].id
}

async function updateBill() {
  await run('PUT bills/{id} 改账单', () =>
    billApi.updateBill(ctx.billId, {
      bookId: ctx.bookId,
      categoryId: ctx.categoryId,
      type: 'EXPENSE',
      amount: 30,
      billTime: nowStr(-2),
      remark: '联调改过',
      // ★ 整体替换语义：必须显式传 accountId（null = 解除关联）
      accountId: ctx.accountId,
    }),
  )
}

async function deleteBill() {
  await run('DELETE bills/{id} 删账单', () => billApi.deleteBill(ctx.billId))
}

async function billSummary() {
  await run('GET bills/summary 本月汇总', () => billApi.billSummary(ctx.bookId))
}

// ============================================================================
// 预算 / 统计
// ============================================================================

async function createBudget() {
  const r = await run<{ id: number }>('POST budgets 建账本级预算', () =>
    billApi.createBudget({ bookId: ctx.bookId, categoryId: null, amount: 3000 }),
  )
  if (r?.id) ctx.budgetId = r.id
}

async function budgetStatus() {
  await run('GET budgets/status 预算状态', () => billApi.budgetStatus({ bookId: ctx.bookId }))
}

async function budgetCalendar() {
  await run('GET budgets/calendar 预算日历', () => billApi.budgetCalendar(ctx.budgetId))
}

async function statsSummary() {
  await run('GET stats/summary 区间汇总', () => billApi.statsSummary({ bookId: ctx.bookId }))
}

async function categoryRatio() {
  await run('GET stats/category-ratio 分类占比', () =>
    billApi.categoryRatio({ bookId: ctx.bookId, drillDown: true }),
  )
}

async function trendDay() {
  await run('GET stats/trend 趋势', () =>
    billApi.trend({ bookId: ctx.bookId, granularity: 'DAY' }),
  )
}

async function aiComment() {
  await run('GET stats/ai-comment AI 点评（较慢）', () =>
    billApi.aiComment({ bookId: ctx.bookId }),
  )
}

// ============================================================================
// AI 记账
// ============================================================================

const aiText = ref('昨天中午吃饭25，打车18，超市买水果56.5')

async function aiParse() {
  const r = await run<{ logId: number }>('POST ai/parse AI 解析', () =>
    aiApi.parseBill(aiText.value),
  )
  if (r?.logId) ctx.logId = r.logId
}

async function aiConfirm() {
  // 为了让联调能跑通，这里**先解析一次再确认**，因为确认需要完整的 bills 结构
  const parsed = await run<AiParseVO>('POST ai/parse（确认前先解析）', () =>
    aiApi.parseBill(aiText.value),
  )
  if (!parsed?.bills?.length) {
    ElMessage.warning('解析没有结果，无法确认')
    return
  }
  await run('POST ai/confirm AI 确认落库', () =>
    aiApi.confirmBills({
      logId: parsed.logId,
      bookId: ctx.bookId,
      bills: parsed.bills.map((b) => ({
        ...b,
        // ★ 草稿里 accountId 恒为 null、categoryId 可能为 null，
        //   正式 UI 要让用户选；联调页用默认值兜底，只为跑通链路
        categoryId: (b.categoryId as number | null) ?? ctx.categoryId,
        accountId: null,
      })) as never,
    }),
  )
}

// ============================================================================
// AI 伙伴
// ============================================================================

const sseStatus = ref<'未连接' | '连接中' | '已连接' | '已断开'>('未连接')
const sseEvents = ref<Array<{ time: string; event: string; data: string }>>([])
let sseConn: chatApi.SseConnection | null = null

function connectSse() {
  if (sseConn) {
    ElMessage.info('已经连接了')
    return
  }
  sseStatus.value = '连接中'
  sseConn = chatApi.connectSse({
    onOpen: () => {
      sseStatus.value = '已连接'
      push('SSE 连接', 'success', '已连接')
    },
    onClose: () => {
      sseStatus.value = '已断开'
      push('SSE 断开', 'network', '连接关闭（会自动重连）')
    },
    onError: (e) => push('SSE 错误', 'network', String(e)),
    onBillCreated: (payload) => {
      sseEvents.value.unshift({
        time: new Date().toLocaleTimeString(),
        event: 'bill-created',
        data: JSON.stringify(payload, null, 2),
      })
      ElMessage.success(`收到账单卡片推送：${payload.billId}`)
    },
  })
}

function disconnectSse() {
  sseConn?.close()
  sseConn = null
  sseStatus.value = '未连接'
}

async function dailyOpen() {
  await run('POST chat/daily-open 每日问候', () => chatApi.dailyOpen())
}

async function pageMessages() {
  await run('GET chat/messages 对话历史', () => chatApi.pageMessages(null, 20))
}

async function deleteMessage() {
  await run('DELETE chat/messages/{id} 删消息', () =>
    chatApi.deleteMessage(ctx.messageId, false),
  )
}

async function deleteMessageCascade() {
  try {
    await ElMessageBox.confirm(
      '这会同时删除对应的账单，并回滚账户余额。确定继续吗？',
      '危险操作',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await run('DELETE chat/messages/{id}?cascadeBill=true 级联删', () =>
    chatApi.deleteMessage(ctx.messageId, true),
  )
}

// ============================================================================
// 角色
// ============================================================================

async function listPresets() {
  await run('GET persona/presets 预设列表', () => personaApi.listPresets())
}

async function createPersona() {
  const r = await run<{ id: number }>('POST persona 从预设创建', () =>
    personaApi.createPersona({ presetId: 3001 }),
  )
  if (r?.id) ctx.personaId = r.id
}

async function listPersonas() {
  const r = await run<Array<{ id: number }>>('GET persona 我的角色', () =>
    personaApi.listMyPersonas(),
  )
  if (r?.length) ctx.personaId = r[0].id
}

async function activePersona() {
  await run('GET persona/active 当前生效', () => personaApi.getActivePersona())
}

async function updatePersona() {
  await run('PUT persona/{id} 改角色', () =>
    personaApi.updatePersona(ctx.personaId, { name: `角色${Date.now() % 1000}` }),
  )
}

async function activatePersona() {
  await run('PUT persona/{id}/activate 切换激活', () =>
    personaApi.activatePersona(ctx.personaId),
  )
}

async function deletePersona() {
  await run('DELETE persona/{id} 删角色', () => personaApi.deletePersona(ctx.personaId))
}

// ============================================================================
// 其它
// ============================================================================

async function changePassword() {
  await run('PUT user/password 改密码（需验证码）', () =>
    userApi.changePassword(ctx.code, 'pass123456'),
  )
}

async function accountCheck() {
  await run('GET dev/account-check 对账自检', () => baseApi.accountCheck())
}

function clearLogs() {
  logs.value = []
}

// ============================================================================
// 一键自检
// ============================================================================

/**
 * 自检结果的一步。
 *
 * ★ 用 `skipped` 单独区分"跳过"和"失败"：
 *   比如可选步骤（AI 点评）失败不该让整体判定为不健康，
 *   但用户仍要知道它没跑成。
 */
interface CheckStep {
  name: string
  status: 'pending' | 'running' | 'ok' | 'fail' | 'skip'
  detail: string
}

const checkSteps = ref<CheckStep[]>([])
const checking = ref(false)

/** 自检过程中按阶段输出的简要日志（比调用日志更易读）。 */
const cbLogs = ref<string[]>([])

/** 当前自检是否全部通过（有 fail 就不通过）。 */
const checkPassed = computed(
  () => checkSteps.value.length > 0 && checkSteps.value.every((s) => s.status !== 'fail'),
)

/**
 * 一键自检：自动跑完「登录 → 取上下文 → 记账 → 查询 → 统计 → 对账」整条链路。
 *
 * ★★ 为什么需要它：
 *   逐个点 54 个按钮太慢，而且**依赖顺序容易点错**（比如没登录就点记账）。
 *   这条链路覆盖了"能不能用"的核心路径，点一次就知道环境是否健康。
 *
 * ★ 设计取舍：
 *   - **不测写操作之外的破坏性接口**（删除类），避免自检把数据删掉
 *   - AI 相关步骤标记为**可选**：AI 挂了不代表后端坏了，但要让用户知道
 *   - 每步独立记录，某步失败**不中断后续**，这样一次就能看到所有问题
 */
async function runSelfCheck() {
  if (checking.value) {
    return
  }
  checking.value = true
  checkSteps.value = []
  cbLogs.value = []

  const steps: CheckStep[] = []
  checkSteps.value = steps

  /** 记录一步并执行；返回结果（失败返回 undefined，不抛出）。 */
  const step = async <T,>(
    name: string,
    fn: () => Promise<T>,
    opts: { optional?: boolean } = {},
  ): Promise<T | undefined> => {
    const s: CheckStep = { name, status: 'running', detail: '' }
    steps.push(s)
    const t0 = performance.now()
    try {
      const r = await fn()
      const ms = Math.round(performance.now() - t0)
      s.status = 'ok'
      s.detail = `${ms}ms`
      return r
    } catch (e) {
      const ms = Math.round(performance.now() - t0)
      if (opts.optional) {
        s.status = 'skip'
        s.detail = `可选步骤未通过（不影响结论）：${e instanceof Error ? e.message : String(e)}`
      } else {
        s.status = 'fail'
        s.detail = `${ms}ms · ${e instanceof Error ? e.message : String(e)}`
      }
      return undefined
    }
  }

  try {
    // ---------- 1. 登录 ----------
    cbLogs.value.push('【1】认证')
    const login = await step('密码登录', () =>
      authApi.loginByPassword(ctx.phone, ctx.password),
    )
    if (!login?.token) {
      // 登录失败后面全没意义，直接结束
      cbLogs.value.push('  ✗ 登录失败，后续步骤无法执行')
      return
    }
    ctx.token = login.token
    setToken(login.token)

    // ---------- 2. 取上下文 ----------
    cbLogs.value.push('【2】上下文')
    const me = await step('user/me 取 userId', () => userApi.getMe())
    if (me?.id) ctx.userId = me.id

    const books = await step('账本列表', () => baseApi.listBooks())
    if (books?.length) ctx.bookId = books[0].id

    const accounts = await step('账户列表', () => baseApi.listAccounts())
    if (accounts?.length) {
      ctx.accountId = accounts[0].id
      ctx.accountId2 = accounts[accounts.length - 1].id
    }

    const tree = await step('分类树', () => baseApi.treeCategories('EXPENSE'))
    if (tree?.length) ctx.categoryId = tree[0].id

    // ---------- 3. 记账 ----------
    cbLogs.value.push('【3】记账与查询')
    // ★ 时间用"1 分钟前"，避免上午跑时落在未来被 BILL_TIME_FUTURE 拒绝
    const billTime = nowStr(-1)
    const bill = await step('新增账单', () =>
      billApi.createBill({
        bookId: ctx.bookId,
        categoryId: ctx.categoryId,
        type: 'EXPENSE',
        amount: 1.11,
        billTime,
        remark: '自检',
        accountId: ctx.accountId,
      }),
    )
    if (bill?.id) ctx.billId = bill.id

    await step('账单详情', () => billApi.getBill(ctx.billId))
    await step('分页查询', () =>
      billApi.pageBills({ bookId: ctx.bookId, page: 1, size: 10 }),
    )
    await step('本月汇总', () => billApi.billSummary(ctx.bookId))

    // ---------- 4. 统计 ----------
    cbLogs.value.push('【4】统计（三个口径应一致）')
    const summary = await step('区间汇总', () => billApi.statsSummary({ bookId: ctx.bookId }))
    await step('分类占比', () => billApi.categoryRatio({ bookId: ctx.bookId }))
    await step('趋势', () => billApi.trend({ bookId: ctx.bookId, granularity: 'DAY' }))

    // ★ 交叉验证：三个接口给的总支出必须相同
    if (summary) {
      const ratio = await billApi
        .categoryRatio({ bookId: ctx.bookId })
        .catch(() => null)
      const trend = await billApi
        .trend({ bookId: ctx.bookId, granularity: 'DAY' })
        .catch(() => null)
      const a = Number(summary.expense)
      const b = ratio ? ratio.reduce((s, r) => s + Number(r.amount), 0) : NaN
      const c = trend ? Number(trend.total) : NaN
      const same = Math.abs(a - b) < 0.01 && Math.abs(a - c) < 0.01
      const s: CheckStep = {
        name: '三个统计口径一致',
        status: same ? 'ok' : 'fail',
        detail: same ? `都是 ${a}` : `汇总=${a} 占比和=${b} 趋势=${c}`,
      }
      steps.push(s)
      cbLogs.value.push(`  ${same ? '✓' : '✗'} 汇总=${a} 占比和=${b} 趋势=${c}`)
    }

    // ---------- 5. 对账 ----------
    cbLogs.value.push('【5】对账')
    const checks = await step('对账自检', () => baseApi.accountCheck())
    if (checks) {
      const bad = checks.filter((c) => !c.matched)
      steps.push({
        name: '所有账户余额与流水一致',
        status: bad.length === 0 ? 'ok' : 'fail',
        detail:
          bad.length === 0
            ? `${checks.length} 个账户全部平衡`
            : `${bad.length} 个账户不平：${bad.map((b) => `${b.name}(差${b.diff})`).join(', ')}`,
      })
    }

    // ---------- 6. 角色 ----------
    cbLogs.value.push('【6】角色')
    await step('预设列表', () => personaApi.listPresets())
    const active = await step('当前生效角色', () => personaApi.getActivePersona())
    if (active === null) {
      // 没有角色是正常状态（新用户），不是错误
      steps.push({
        name: '生效角色',
        status: 'ok',
        detail: '当前无角色（正常，新用户还没选）',
      })
    }

    // ---------- 7. AI（可选）----------
    cbLogs.value.push('【7】AI 能力（可选，失败不影响结论）')
    await step('AI 解析', () => aiApi.parseBill('自检：吃饭25'), { optional: true })
    await step('AI 点评', () => billApi.aiComment({ bookId: ctx.bookId }), { optional: true })

    // ---------- 8. 收尾：删掉自检产生的账单 ----------
    cbLogs.value.push('【8】清理')
    if (ctx.billId) {
      await step('删除自检账单', () => billApi.deleteBill(ctx.billId), { optional: true })
    }
  } finally {
    checking.value = false
    const failCount = steps.filter((s) => s.status === 'fail').length
    const skipCount = steps.filter((s) => s.status === 'skip').length
    if (failCount === 0) {
      ElMessage.success(
        `自检通过（${steps.length} 步${skipCount ? `，${skipCount} 步可选未通过` : ''}）`,
      )
    } else {
      ElMessage.error(`自检发现 ${failCount} 个问题`)
    }
  }
}

onBeforeUnmount(() => {
  // ★ 组件卸载必须断开 SSE，否则连接泄漏
  sseConn?.close()
  sseConn = null
})

// ============================================================================
// 分组渲染
// ============================================================================

interface Action {
  label: string
  /** 返回值只用于调试展示，类型不重要，这里放宽到 unknown */
  handler: () => unknown | Promise<unknown>
  danger?: boolean
}

interface Group {
  title: string
  hint?: string
  actions: Action[]
}

const groups: Group[] = [
  {
    title: '① 认证',
    hint: '先点「一键启动」走完登录。验证码去后端日志或 Redis 里找（开发环境不真发短信）',
    actions: [
      { label: '一键启动（登录+取上下文）', handler: bootstrap },
      { label: 'send-code', handler: doSendCode },
      { label: 'register 注册', handler: doRegister },
      { label: 'login/password', handler: doLoginPassword },
      { label: 'login/code', handler: doLoginCode },
      { label: 'reset-password', handler: () => run('reset-password 忘记密码', () => authApi.resetPassword(ctx.phone, ctx.code, 'pass123456')) },
      { label: 'logout', handler: doLogout, danger: true },
    ],
  },
  {
    title: '② 用户',
    actions: [
      { label: 'user/me', handler: () => run('GET user/me', () => userApi.getMe()) },
      { label: 'PUT password（需验证码）', handler: changePassword },
    ],
  },
  {
    title: '③ 账本',
    actions: [
      { label: 'GET 列表', handler: () => run('GET books', () => baseApi.listBooks()) },
      { label: 'POST 建', handler: createBook },
      { label: 'PUT 改', handler: updateBook },
      { label: 'DELETE 删', handler: deleteBook, danger: true },
    ],
  },
  {
    title: '④ 分类',
    actions: [
      { label: 'GET 树', handler: () => run('GET categories', () => baseApi.treeCategories('EXPENSE')) },
      { label: 'POST 建', handler: createCategory },
      { label: 'PUT 改', handler: updateCategory },
      { label: 'DELETE 删', handler: deleteCategory, danger: true },
    ],
  },
  {
    title: '⑤ 账户',
    hint: '转账返回批次号，撤销要用它',
    actions: [
      { label: 'GET 列表', handler: () => run('GET accounts', () => baseApi.listAccounts()) },
      { label: 'POST 建', handler: createAccount },
      { label: 'PUT 改', handler: updateAccount },
      { label: 'POST 转账', handler: doTransfer },
      { label: 'DELETE 撤销转账', handler: cancelTransfer },
      { label: 'POST 校正余额', handler: doAdjust },
      { label: 'GET 流水', handler: listFlows },
      { label: 'DELETE 删账户', handler: deleteAccount, danger: true },
    ],
  },
  {
    title: '⑥ 账单',
    hint: '时间用「1 分钟前」，避免上午跑时落在未来被拒',
    actions: [
      { label: 'POST 记账', handler: createBill },
      { label: 'GET 分页', handler: pageBills },
      { label: 'GET 详情', handler: () => run('GET bills/{id}', () => billApi.getBill(ctx.billId)) },
      { label: 'PUT 改', handler: updateBill },
      { label: 'GET summary', handler: billSummary },
      { label: 'DELETE 删', handler: deleteBill, danger: true },
    ],
  },
  {
    title: '⑦ 预算',
    actions: [
      { label: 'GET 列表', handler: () => run('GET budgets', () => billApi.listBudgets(ctx.bookId)) },
      { label: 'POST 建', handler: createBudget },
      { label: 'GET status', handler: budgetStatus },
      { label: 'GET calendar', handler: budgetCalendar },
      { label: 'DELETE 删', handler: () => run('DELETE budgets/{id}', () => billApi.deleteBudget(ctx.budgetId)), danger: true },
    ],
  },
  {
    title: '⑧ 统计',
    hint: 'AI 点评可能要等十几秒，且失败时 data 为 null（不是错误）',
    actions: [
      { label: 'GET summary', handler: statsSummary },
      { label: 'GET category-ratio', handler: categoryRatio },
      { label: 'GET trend', handler: trendDay },
      { label: 'GET ai-comment', handler: aiComment },
    ],
  },
  {
    title: '⑨ AI 记账',
    hint: '两段式：parse 出草稿（不落库）→ confirm 才落库',
    actions: [
      { label: 'POST parse', handler: aiParse },
      { label: 'POST confirm', handler: aiConfirm },
    ],
  },
  {
    title: '⑩ AI 伙伴',
    hint: '先连 SSE，再点「记账」，应能收到 bill-created 推送',
    actions: [
      { label: 'POST daily-open 问候', handler: dailyOpen },
      { label: 'GET messages 历史', handler: pageMessages },
      { label: 'DELETE 删消息', handler: deleteMessage, danger: true },
      { label: 'DELETE 级联删（危险）', handler: deleteMessageCascade, danger: true },
    ],
  },
  {
    title: '⑪ 角色',
    actions: [
      { label: 'GET presets', handler: listPresets },
      { label: 'POST 从预设建', handler: createPersona },
      { label: 'GET 我的角色', handler: listPersonas },
      { label: 'GET active', handler: activePersona },
      { label: 'PUT 改', handler: updatePersona },
      { label: 'PUT activate', handler: activatePersona },
      { label: 'DELETE 删', handler: deletePersona, danger: true },
    ],
  },
  {
    title: '⑫ 自检',
    actions: [{ label: 'GET 对账', handler: accountCheck }],
  },
]

const kindClass: Record<LogEntry['kind'], string> = {
  success: 'kind-ok',
  business: 'kind-biz',
  network: 'kind-err',
}
const kindText: Record<LogEntry['kind'], string> = {
  success: '成功',
  business: '业务失败',
  network: '网络/鉴权异常',
}
</script>

<template>
  <div class="page">
    <header class="head">
      <h1>接口联调页</h1>
      <p class="sub">
        后端 <code>http://127.0.0.1:8080</code> · 经 Vite 代理（无 CORS）· 覆盖 54 个端点
      </p>
    </header>

    <!-- 上下文 -->
    <section class="card">
      <h2>上下文</h2>
      <p class="hint">登录后自动填充；也可手动改，方便测越权等场景</p>
      <div class="ctx-grid">
        <label>手机号 <input v-model="ctx.phone" /></label>
        <label>密码 <input v-model="ctx.password" /></label>
        <label>验证码 <input v-model="ctx.code" placeholder="6 位数字" /></label>
        <label>token <input v-model="ctx.token" class="mono" /></label>
        <label>userId <input v-model.number="ctx.userId" type="number" /></label>
        <label>bookId <input v-model.number="ctx.bookId" type="number" /></label>
        <label>accountId <input v-model.number="ctx.accountId" type="number" /></label>
        <label>accountId2 <input v-model.number="ctx.accountId2" type="number" /></label>
        <label>categoryId <input v-model.number="ctx.categoryId" type="number" /></label>
        <label>billId <input v-model.number="ctx.billId" type="number" /></label>
        <label>budgetId <input v-model.number="ctx.budgetId" type="number" /></label>
        <label>personaId <input v-model.number="ctx.personaId" type="number" /></label>
        <label>logId <input v-model.number="ctx.logId" type="number" /></label>
        <label>messageId <input v-model.number="ctx.messageId" type="number" /></label>
        <label class="wide">transferNo <input v-model="ctx.transferNo" class="mono" /></label>
      </div>
    </section>

    <!-- 一键自检 -->
    <section class="card selfcheck">
      <h2>
        一键自检
        <button class="btn btn-primary" :disabled="checking" @click="runSelfCheck">
          {{ checking ? '自检中…' : '开始自检' }}
        </button>
      </h2>
      <p class="hint">
        自动跑完「登录 → 取上下文 → 记账 → 查询 → 统计 → 对账 → 角色 → AI」，每步独立判定。
        <b>某步失败不会中断</b>，一次就能看到所有问题。自检产生的账单会自动删掉。
      </p>

      <!-- 总体结论 -->
      <div
        v-if="checkSteps.length && !checking"
        :class="['verdict', checkPassed ? 'verdict-ok' : 'verdict-bad']"
      >
        {{ checkPassed ? '✓ 环境健康，可以开始写正式 UI' : '✗ 有问题，见下方红色步骤' }}
      </div>

      <!-- 步骤列表 -->
      <div v-if="checkSteps.length" class="steps">
        <div v-for="(s, i) in checkSteps" :key="i" :class="['step', 'step-' + s.status]">
          <span class="step-icon">
            <template v-if="s.status === 'ok'">✓</template>
            <template v-else-if="s.status === 'fail'">✗</template>
            <template v-else-if="s.status === 'skip'">○</template>
            <template v-else-if="s.status === 'running'">…</template>
            <template v-else>·</template>
          </span>
          <span class="step-name">{{ s.name }}</span>
          <span class="step-detail">{{ s.detail }}</span>
        </div>
      </div>

      <!-- 阶段日志 -->
      <pre v-if="cbLogs.length" class="cb-logs">{{ cbLogs.join('\n') }}</pre>
    </section>

    <!-- 分组按钮 -->
    <section v-for="g in groups" :key="g.title" class="card">
      <h2>{{ g.title }}</h2>
      <p v-if="g.hint" class="hint">{{ g.hint }}</p>
      <div class="btns">
        <button
          v-for="a in g.actions"
          :key="a.label"
          :class="['btn', a.danger ? 'btn-danger' : '']"
          :disabled="running === a.label"
          @click="a.handler()"
        >
          {{ running === a.label ? '…' : a.label }}
        </button>
      </div>
    </section>

    <!-- SSE -->
    <section class="card">
      <h2>SSE 推送</h2>
      <p class="hint">
        状态：<b>{{ sseStatus }}</b>。连接后去上面「⑥ 账单」点「POST 记账」，这里应出现 bill-created
      </p>
      <div class="btns">
        <button class="btn" :disabled="sseStatus === '已连接'" @click="connectSse">连接</button>
        <button class="btn btn-danger" @click="disconnectSse">断开</button>
      </div>
      <div v-if="sseEvents.length" class="sse-list">
        <div v-for="(e, i) in sseEvents" :key="i" class="sse-item">
          <div class="sse-head">{{ e.time }} · {{ e.event }}</div>
          <pre>{{ e.data }}</pre>
        </div>
      </div>
      <p v-else class="empty">还没有收到推送</p>
    </section>

    <!-- 日志 -->
    <section class="card">
      <h2>
        调用日志
        <label class="inline-check">
          <input v-model="onlyFailures" type="checkbox" /> 只看失败
        </label>
        <button class="btn btn-small" @click="clearLogs">清空</button>
      </h2>
      <p class="hint">
        <span class="tag kind-ok">成功</span>
        <span class="tag kind-biz">业务失败（HTTP 200 但 code≠200）</span>
        <span class="tag kind-err">网络/鉴权异常</span>
      </p>
      <div v-if="!shownLogs.length" class="empty">还没有调用</div>
      <div v-for="(l, i) in shownLogs" :key="i" :class="['log', kindClass[l.kind]]">
        <div class="log-head">
          <span class="time">{{ l.time }}</span>
          <span class="kind">{{ kindText[l.kind] }}</span>
          <span class="name">{{ l.name }}</span>
        </div>
        <pre>{{ l.detail }}</pre>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page {
  max-width: 1100px;
  margin: 0 auto;
  padding: 24px 16px 64px;
  font-family: system-ui, -apple-system, 'Segoe UI', sans-serif;
  color: #1f2328;
}
.head h1 {
  margin: 0 0 6px;
  font-size: 22px;
}
.sub {
  margin: 0 0 20px;
  color: #6b7280;
  font-size: 13px;
}
.card {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 16px;
  margin-bottom: 14px;
}
.card h2 {
  margin: 0 0 6px;
  font-size: 15px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.hint {
  margin: 0 0 10px;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.6;
}
.ctx-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 8px;
}
.ctx-grid label {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 12px;
  color: #4b5563;
}
.ctx-grid input {
  padding: 5px 8px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 13px;
}
.ctx-grid .wide {
  grid-column: span 2;
}
.mono {
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px !important;
}
.btns {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.btn {
  padding: 6px 12px;
  border: 1px solid #d1d5db;
  background: #f9fafb;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}
.btn:hover:not(:disabled) {
  background: #eef2ff;
  border-color: #a5b4fc;
}
.btn:disabled {
  opacity: 0.5;
  cursor: default;
}
.btn-danger {
  color: #b91c1c;
  border-color: #fecaca;
  background: #fef2f2;
}
.btn-small {
  padding: 3px 8px;
  font-size: 12px;
}
.inline-check {
  font-size: 12px;
  font-weight: normal;
  color: #4b5563;
}
.tag {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 4px;
  margin-right: 6px;
  font-size: 11px;
}
.kind-ok .kind,
.tag.kind-ok {
  color: #15803d;
}
.tag.kind-ok {
  background: #dcfce7;
}
.kind-biz .kind,
.tag.kind-biz {
  color: #b45309;
}
.tag.kind-biz {
  background: #fef3c7;
}
.kind-err .kind,
.tag.kind-err {
  color: #b91c1c;
}
.tag.kind-err {
  background: #fee2e2;
}
.log {
  border-left: 3px solid #e5e7eb;
  padding: 8px 10px;
  margin-top: 8px;
  background: #fafafa;
  border-radius: 0 6px 6px 0;
}
.log.kind-ok {
  border-left-color: #22c55e;
}
.log.kind-biz {
  border-left-color: #f59e0b;
}
.log.kind-err {
  border-left-color: #ef4444;
}
.log-head {
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: 12px;
}
.log-head .time {
  color: #9ca3af;
  font-family: ui-monospace, monospace;
}
.log-head .name {
  font-weight: 600;
}
.log pre {
  margin: 6px 0 0;
  font-size: 11px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 260px;
  overflow: auto;
  color: #374151;
}
.sse-list {
  margin-top: 10px;
}
.sse-item {
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  padding: 8px 10px;
  margin-bottom: 8px;
}
.sse-head {
  font-size: 12px;
  color: #6b7280;
  font-family: ui-monospace, monospace;
}
.sse-item pre {
  margin: 6px 0 0;
  font-size: 11px;
  white-space: pre-wrap;
  word-break: break-all;
}
.empty {
  color: #9ca3af;
  font-size: 13px;
  margin: 8px 0 0;
}
code {
  background: #f3f4f6;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 12px;
}

/* ---------- 一键自检 ---------- */
.selfcheck {
  border-color: #c7d2fe;
  background: linear-gradient(180deg, #f5f7ff 0%, #fff 60px);
}
.btn-primary {
  background: #4f46e5;
  color: #fff;
  border-color: #4f46e5;
}
.btn-primary:hover:not(:disabled) {
  background: #4338ca;
  border-color: #4338ca;
}
.verdict {
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  margin: 4px 0 10px;
}
.verdict-ok {
  background: #dcfce7;
  color: #15803d;
}
.verdict-bad {
  background: #fee2e2;
  color: #b91c1c;
}
.steps {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.step {
  display: grid;
  grid-template-columns: 18px 200px 1fr;
  gap: 8px;
  align-items: baseline;
  padding: 3px 4px;
  font-size: 12px;
  border-radius: 4px;
}
.step:nth-child(odd) {
  background: #fafafa;
}
.step-icon {
  text-align: center;
  font-weight: 700;
}
.step-name {
  color: #374151;
}
.step-detail {
  color: #6b7280;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  word-break: break-all;
}
.step-ok .step-icon {
  color: #22c55e;
}
.step-fail {
  background: #fef2f2 !important;
}
.step-fail .step-icon {
  color: #ef4444;
}
.step-fail .step-name {
  color: #b91c1c;
  font-weight: 600;
}
.step-fail .step-detail {
  color: #b91c1c;
}
.step-skip .step-icon {
  color: #f59e0b;
}
.step-skip .step-detail {
  color: #b45309;
}
.step-running .step-icon {
  color: #4f46e5;
}
.cb-logs {
  margin: 10px 0 0;
  padding: 8px 10px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  font-size: 11px;
  line-height: 1.6;
  max-height: 180px;
  overflow: auto;
  color: #334155;
}
</style>
