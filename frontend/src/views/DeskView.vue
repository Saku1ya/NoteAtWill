<script setup lang="ts">
/**
 * 记账台（首页）。
 *
 * ★★ 这是全项目最关键的页面，把「说一句话 → AI 拆笔 → 逐笔核对 → 落库」
 *   这条主流程完整跑通。接口契约的几个硬约束（都在这里落实）：
 *
 *   1. `parseBill` **不落库**，只返回草稿 + logId；logId 必须在 confirm 时带回。
 *   2. `bills[].categoryId` **可能为 null**（模型没匹配到分类），
 *      而 t_bill.category_id 是 NOT NULL —— 所以**确认前必须逐笔校验**，
 *      否则后端整批回滚，用户只会看到一句"参数错误"，不知道是哪一笔。
 *   3. `bills[].accountId` **恒为 null**，只有 `accountHint` 名字提示。
 *      这里按名字预选，用户可改。
 *   4. confirm 是**单事务全有或全无**，所以前端先把错误挑出来，
 *      点对点标红，不要让用户去猜。
 *   5. 确认成功后**既拿到返回值、又收到 SSE 推送** → 必须按 billId 去重。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { parseBill, confirmBills } from '../api/ai'
import {
  billSummary,
  pageBills,
  deleteBill,
  budgetStatus,
  categoryRatio,
  trend,
} from '../api/bill'
import { treeCategories, listAccounts } from '../api/base'
import { connectSse, dailyOpen, type SseConnection } from '../api/chat'
import { BusinessError } from '../api/request'
import { useBookStore } from '../stores'
import ChatStream from '../components/ChatStream.vue'
import BudgetEditDialog from '../components/BudgetEditDialog.vue'
import type {
  AccountVO,
  AiBillItem,
  BillSummaryVO,
  BillVO,
  BudgetStatusVO,
  CategoryRatioVO,
  CategoryTreeVO,
  TrendVO,
} from '../api/types'

const bookStore = useBookStore()

/** 对话流组件引用，记账成功后要让它重新拉消息 */
const streamRef = ref<InstanceType<typeof ChatStream> | null>(null)

// ============================================================================
// 基础数据
// ============================================================================

const expenseTree = ref<CategoryTreeVO[]>([])
const incomeTree = ref<CategoryTreeVO[]>([])
const accounts = ref<AccountVO[]>([])

const summary = ref<BillSummaryVO | null>(null)
const recent = ref<BillVO[]>([])
const ratios = ref<CategoryRatioVO[]>([])
const trendVO = ref<TrendVO | null>(null)
const budgets = ref<BudgetStatusVO[]>([])

/** 当前账本的日期区间：本月 1 号 → 今天 */
function monthRange() {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth(), 1)
  const fmt = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return { start: fmt(start), end: fmt(now) }
}

/**
 * 近 6 个月的区间。
 *
 * ★★ 必须同时传区间和 granularity=MONTH：
 *   不传时后端默认"当前自然月 + 按天"，首页那条趋势线会变成 20 个点，
 *   X 轴挤成 10-01…10-20，完全看不出趋势（实测踩过）。
 */
function sixMonthRange() {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth() - 5, 1)
  const fmt = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return { start: fmt(start), end: fmt(now) }
}

async function loadAll() {
  const bookId = bookStore.currentId
  if (!bookId) return
  const { start, end } = monthRange()
  const six = sixMonthRange()

  // ★ 并行拉，避免串行等待
  const [sum, page, ratio, tr, bud, exp, inc, acc] = await Promise.allSettled([
    billSummary(bookId),
    pageBills({ bookId, page: 1, size: 6 }),
    categoryRatio({ bookId, type: 'EXPENSE', start, end }),
    trend({
      bookId,
      type: 'EXPENSE',
      start: six.start,
      end: six.end,
      granularity: 'MONTH',
    }),
    budgetStatus({ bookId }),
    treeCategories('EXPENSE'),
    treeCategories('INCOME'),
    listAccounts(),
  ])

  if (sum.status === 'fulfilled') summary.value = sum.value
  if (page.status === 'fulfilled') recent.value = page.value.records
  if (ratio.status === 'fulfilled') ratios.value = ratio.value
  if (tr.status === 'fulfilled') trendVO.value = tr.value
  if (bud.status === 'fulfilled') budgets.value = bud.value
  if (exp.status === 'fulfilled') expenseTree.value = exp.value
  if (inc.status === 'fulfilled') incomeTree.value = inc.value
  if (acc.status === 'fulfilled') accounts.value = acc.value
}

// ============================================================================
// 对话问候
// ============================================================================

const greeting = ref<{ content: string; degraded: boolean } | null>(null)

async function loadGreeting() {
  try {
    const g = await dailyOpen()
    // ★ alreadySent=true 表示今天问候过了，不要重复播报
    greeting.value = { content: g.content, degraded: g.degraded }
  } catch {
    // 问候失败不影响记账，静默降级
    greeting.value = null
  }
}

// ============================================================================
// AI 解析（第一段）
// ============================================================================

interface DraftRow extends AiBillItem {
  /** 前端临时 id，只用于 :key */
  uid: number
  checked: boolean
}

const text = ref('')
const parsing = ref(false)
/** 解析失败的原因（AI 不可用 / 超时 / 没识别出东西），用于给用户兜底提示 */
const parseError = ref('')
const logId = ref<number | null>(null)
const draft = ref<DraftRow[]>([])
/** 未被识别的片段（后端可能有，也可能没有，这里留扩展位） */
const unmatched = ref<string[]>([])

let uid = 1

async function doParse() {
  const t = text.value.trim()
  if (!t || parsing.value || !bookStore.currentId) return

  parsing.value = true
  parseError.value = ''
  try {
    const r = await parseBill(t)
    logId.value = r.logId
    draft.value = (r.bills ?? []).map((b) => ({
      ...b,
      uid: uid++,
      checked: true,
      // ★ accountId 恒为 null：按 accountHint 尝试预选（决策①早就定了）
      accountId: b.accountId ?? null,
    }))
    // 预选账户：名字能对上就选上，用户可改
    for (const d of draft.value) {
      if (d.accountId == null && d.accountHint) {
        const hit = accounts.value.find(
          (a) => a.name === d.accountHint || a.name.includes(d.accountHint!),
        )
        if (hit) d.accountId = hit.id
      }
    }
    text.value = ''
    if (!draft.value.length) {
      parseError.value = '这句我没听出金额，换个说法再说一遍？'
    }
  } catch (e) {
    // ★ 兜底文案按"有人味的助手"写，不暴露"模板/服务不可用"这类实现细节
    const msg = e instanceof BusinessError ? e.message : '我这会儿有点反应不过来'
    // ★ 解析失败要引导到手动记账，不能让用户卡住
    parseError.value = `${msg}。要不先手动记一笔？或者过会儿再跟我说一遍。`
  } finally {
    parsing.value = false
  }
}

function cancelDraft() {
  draft.value = []
  logId.value = null
  parseError.value = ''
  unmatched.value = []
}

// ============================================================================
// 草稿编辑
// ============================================================================

/** 当前类型的分类树 —— 支出和收入是两棵独立的树 */
function treeOf(type: 'EXPENSE' | 'INCOME') {
  return type === 'EXPENSE' ? expenseTree.value : incomeTree.value
}

/** ★ 切类型必须清空分类：两棵树的 id 不通用，否则后端报 3015 */
function onTypeChange(d: DraftRow) {
  d.categoryId = null
  d.categoryName = null
}

function onCategoryChange(d: DraftRow) {
  if (d.categoryId == null) {
    d.categoryName = null
    return
  }
  for (const p of treeOf(d.type)) {
    if (p.id === d.categoryId) {
      d.categoryName = p.name
      return
    }
    const c = p.children?.find((x) => x.id === d.categoryId)
    if (c) {
      d.categoryName = `${p.name}/${c.name}`
      return
    }
  }
}

const checkedRows = computed(() => draft.value.filter((d) => d.checked))

/** ★ 缺分类的行：这是 confirm 会被拒的唯一原因，必须提前拦 */
const missingCategory = computed(() => checkedRows.value.filter((d) => d.categoryId == null))

const canConfirm = computed(
  () => checkedRows.value.length > 0 && missingCategory.value.length === 0 && !submitting.value,
)

const totalExpense = computed(() =>
  checkedRows.value
    .filter((d) => d.type === 'EXPENSE')
    .reduce((s, d) => s + Number(d.amount || 0), 0),
)
const totalIncome = computed(() =>
  checkedRows.value
    .filter((d) => d.type === 'INCOME')
    .reduce((s, d) => s + Number(d.amount || 0), 0),
)

/** 低置信度阈值 */
const LOW = 0.7
function isLow(d: DraftRow) {
  const c = d.confidence == null ? 1 : Number(d.confidence)
  return c < LOW
}

// ============================================================================
// 确认落库（第二段）
// ============================================================================

const submitting = ref(false)

async function doConfirm() {
  if (!canConfirm.value || logId.value == null || !bookStore.currentId) return
  submitting.value = true
  try {
    const payload = checkedRows.value.map<AiBillItem>((d) => ({
      amount: d.amount,
      type: d.type,
      categoryId: d.categoryId,
      categoryName: d.categoryName,
      accountId: d.accountId,
      accountHint: d.accountHint,
      billTime: d.billTime,
      remark: d.remark,
      confidence: d.confidence,
    }))

    const saved = await confirmBills({
      logId: logId.value,
      bookId: bookStore.currentId,
      bills: payload,
    })

    // ★ 确认成功的返回值 + SSE 推送会各来一次 → 按 id 去重
    for (const b of saved ?? []) upsertRecent(b)

    ElMessage.success(`已入账 ${saved?.length ?? payload.length} 笔`)
    cancelDraft()
    // ★ 入账后对话流里会多出账单卡片，要重新拉一次
    streamRef.value?.reload()
    await loadAll()
  } catch (e) {
    const msg = e instanceof BusinessError ? e.message : '确认失败，请稍后重试'
    ElMessage.error(msg)
  } finally {
    submitting.value = false
  }
}

/** 按 id 去重地插入最近列表（SSE 与 confirm 返回值可能重复）。 */
function upsertRecent(b: BillVO) {
  const i = recent.value.findIndex((x) => x.id === b.id)
  if (i >= 0) recent.value[i] = b
  else recent.value = [b, ...recent.value].slice(0, 6)
}

// ============================================================================
// SSE：只做通知，收到后按 billId 去重
// ============================================================================

let sse: SseConnection | null = null
const sseOn = ref(false)

onMounted(async () => {
  await loadAll()
  loadGreeting()

  sse = connectSse({
    onOpen: () => (sseOn.value = true),
    onClose: () => (sseOn.value = false),
    onBillCreated: (p) => {
      // ★ 别的标签页记的账也会推到这里，用 billId 去重
      if (recent.value.some((x) => x.id === p.billId)) return
      // 推送里没有完整 VO，简单起见重拉一次最近列表与汇总
      loadAll()
    },
  })
})

onBeforeUnmount(() => {
  // ★ 不关会泄漏连接，服务端也会越积越多
  sse?.close()
  sse = null
})

// ============================================================================
// 最近一笔的删除（对应设计稿的卡片删除）
// ============================================================================

async function removeBill(b: BillVO) {
  try {
    await ElMessageBox.confirm(
      `删除后这笔账会从统计和预算里一起消失，且会删掉对应的对话卡片。`,
      `删除「${b.remark || b.categoryName || '这笔账'}」？`,
      { confirmButtonText: '删除', cancelButtonText: '算了', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteBill(b.id)
    ElMessage.success('已删除')
    await loadAll()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '删除失败')
  }
}

// ============================================================================
// 派生展示值
// ============================================================================

function money(s: string | null | undefined) {
  const n = Number(s ?? 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function signed(b: BillVO) {
  return `${b.type === 'INCOME' ? '+' : '−'}${money(b.amount)}`
}

/** 只取前 4 个分类做占比条 */
const topRatios = computed(() => ratios.value.slice(0, 4))
const maxRatio = computed(() => Math.max(...topRatios.value.map((r) => Number(r.amount)), 1))

/** 账本级预算（categoryId === null） */
const bookBudget = computed(() => budgets.value.find((b) => b.categoryId == null) ?? null)

// ============================================================================
// 预算编辑（与预算页共用 BudgetEditDialog）
// ============================================================================

/** 预算编辑弹窗：target 为 null 表示新建，传预算条目表示修改 */
const budgetDialogOpen = ref(false)
const budgetDialogTarget = ref<BudgetStatusVO | null>(null)

function openBudgetEdit(b: BudgetStatusVO) {
  budgetDialogTarget.value = b
  budgetDialogOpen.value = true
}

/** 只刷新预算，不重跑整页 8 个接口 */
async function refreshBudgets() {
  const bookId = bookStore.currentId
  if (!bookId) return
  // allSettled 返回的是数组，要解构出第一个元素
  const [bud] = await Promise.allSettled([budgetStatus({ bookId })])
  if (bud.status === 'fulfilled') budgets.value = bud.value
}

/** 趋势折线的 path —— 手写 SVG，不引图表库（首页只需要一条线） */
const trendPath = computed(() => {
  const pts = trendVO.value?.points ?? []
  if (pts.length < 2) return null
  const vals = pts.map((p) => Number(p.amount))
  const max = Math.max(...vals, 1)
  const min = Math.min(...vals, 0)
  const span = max - min || 1
  const W = 100
  const H = 100
  const step = W / (pts.length - 1)
  const coords = vals.map((v, i) => {
    const x = i * step
    const y = H - ((v - min) / span) * (H - 12) - 6
    return `${x.toFixed(2)},${y.toFixed(2)}`
  })
  return {
    line: `M${coords.join(' L')}`,
    area: `M${coords.join(' L')} L${W},${H} L0,${H} Z`,
    last: coords[coords.length - 1].split(',').map(Number),
    points: pts,
  }
})

function ratioPct(r: CategoryRatioVO) {
  return Number(r.percent ?? 0).toFixed(1)
}
</script>

<template>
  <div class="desk">
    <!-- ==================== 左：待确认（主任务） ==================== -->
    <section class="cell draft">
      <div class="draft-head">
        <span class="naw-label">
          待确认
          <span v-if="draft.length" class="cnt">{{ draft.length }} 笔</span>
        </span>
      </div>

      <!-- 有草稿 -->
      <template v-if="draft.length">
        <div class="rows">
          <div
            v-for="d in draft"
            :key="d.uid"
            :class="['row', { 'row--off': !d.checked, 'row--low': isLow(d) }]"
          >
            <input v-model="d.checked" type="checkbox" class="row-check" />

            <div class="row-main">
              <div class="row-top">
                <input
                  v-model="d.remark"
                  class="row-remark"
                  placeholder="这笔是什么"
                  maxlength="50"
                />
                <input
                  v-model="d.amount"
                  class="row-amount naw-num"
                  inputmode="decimal"
                  aria-label="金额"
                />
              </div>

              <div class="row-meta">
                <select v-model="d.type" class="mini" @change="onTypeChange(d)">
                  <option value="EXPENSE">支出</option>
                  <option value="INCOME">收入</option>
                </select>

                <select
                  v-model.number="d.categoryId"
                  :class="['mini', { 'mini--err': d.categoryId == null }]"
                  @change="onCategoryChange(d)"
                >
                  <option :value="null">{{ d.categoryId == null ? '⚠ 选分类' : '选分类' }}</option>
                  <optgroup v-for="p in treeOf(d.type)" :key="p.id" :label="p.name">
                    <option :value="p.id">{{ p.name }}（一级）</option>
                    <option v-for="c in p.children" :key="c.id" :value="c.id">
                      {{ p.name }}/{{ c.name }}
                    </option>
                  </optgroup>
                </select>

                <select v-model.number="d.accountId" class="mini">
                  <option :value="null">未关联账户</option>
                  <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
                </select>
              </div>

              <div class="row-foot">
                <span v-if="d.billTime" class="tm naw-mono">{{ d.billTime }}</span>
                <span v-if="isLow(d)" class="low">
                  置信度 {{ Number(d.confidence ?? 0).toFixed(2) }} · 建议核对
                </span>
                <span v-if="d.accountHint && d.accountId == null" class="hint">
                  模型猜是「{{ d.accountHint }}」
                </span>
              </div>
            </div>
          </div>
        </div>

        <div class="draft-foot">
          <div class="totals">
            <span v-if="totalExpense > 0" class="t">
              支出 <b class="naw-num">{{ money(String(totalExpense)) }}</b>
            </span>
            <span v-if="totalIncome > 0" class="t">
              收入 <b class="naw-num naw-num--income">{{ money(String(totalIncome)) }}</b>
            </span>
          </div>
          <div class="acts">
            <button class="naw-textbtn" @click="cancelDraft">取消</button>
            <button class="sealbtn" :disabled="!canConfirm" @click="doConfirm">
              <span class="seal">记</span>
              <span>{{
                submitting
                  ? '入账中…'
                  : missingCategory.length
                    ? `还有 ${missingCategory.length} 笔缺分类`
                    : `入账 ${checkedRows.length} 笔`
              }}</span>
            </button>
          </div>
        </div>
      </template>

      <!-- ★★ 无草稿时显示**对话流**：AI 的问候、记过的账、说过的话都在这里。
           之前这一块只有一句问候 + 大片空白，用户反馈"不展示任何对话"。
           草稿出现时切回待确认列表（两者互斥，同一时刻只做一件事）。 -->
      <template v-else>
        <div v-if="parseError" class="parse-err">
          <p class="err-text">{{ parseError }}</p>
          <router-link :to="{ name: 'bills' }" class="link">去账单页手动记一笔 →</router-link>
        </div>
        <ChatStream ref="streamRef" />
      </template>

      <!-- ★★ 输入框放在 AI 对话栏的底部（不是页面最底那条细线）。
           理由：用户第一眼找不到页面底部的输入条 —— 它太窄、颜色太淡。
           放回草稿正下方之后，「打字 → 草稿出现在头顶 → 确认」是一条连续的视线。 -->
      <div class="ask">
        <span class="ask-label">说一句，我来拆</span>
        <div class="ask-box">
          <input
            v-model="text"
            class="ask-input"
            placeholder="比如：昨晚和朋友吃饭 268"
            :disabled="parsing"
            maxlength="500"
            @keyup.enter="doParse"
          />
          <button class="ask-go" :disabled="!text.trim() || parsing" @click="doParse">
            {{ parsing ? '…' : '记下' }}
          </button>
        </div>
        <span v-if="parsing" class="ask-parsing">
          <span class="naw-dots"><i /><i /><i /></span>
          正在识别
        </span>
        <span v-else class="ask-hint">Enter 直接提交 · 会先给你草稿，确认后才入账</span>
      </div>
    </section>

    <!-- ==================== 右上：本月 ==================== -->
    <section class="cell cell--hb month">
      <span class="naw-label">本月 · {{ summary?.month ?? '' }}</span>
      <div class="month-big">
        <div>
          <em>支出</em>
          <span class="naw-num">{{ money(summary?.expense) }}</span>
        </div>
        <div>
          <em>收入</em>
          <span class="naw-num naw-num--income">{{ money(summary?.income) }}</span>
        </div>
        <div class="net">
          <em>结余</em>
          <span class="naw-num">{{ money(summary?.surplus) }}</span>
        </div>
      </div>
    </section>

    <!-- ==================== 右中：分类占比 ==================== -->
    <section class="cell cell--vr cell--hb share">
      <span class="naw-label">分类占比 <span class="naw-label__sub">本月支出</span></span>
      <div v-if="topRatios.length" class="track">
        <div v-for="r in topRatios" :key="r.categoryId" class="bar">
          <span class="k">{{ r.categoryName || '未分类' }}</span>
          <span class="g"><i :style="{ width: (Number(r.amount) / maxRatio) * 100 + '%' }" /></span>
          <span class="v naw-num">{{ money(r.amount) }}</span>
          <span class="p mono">{{ ratioPct(r) }}%</span>
        </div>
      </div>
      <div v-else class="naw-empty">本月还没有支出</div>
    </section>

    <!-- ==================== 右下：最近 ==================== -->
    <section class="cell cell--hb recent">
      <span class="naw-label">最近</span>
      <ul v-if="recent.length" class="recent-list">
        <li v-for="b in recent" :key="b.id" class="recent-row">
          <span class="nm" :title="b.remark || ''">
            {{ b.remark || b.categoryName || '未命名' }}
          </span>
          <span :class="['am', 'naw-num', { 'naw-num--income': b.type === 'INCOME' }]">
            {{ signed(b) }}
          </span>
          <button class="del" title="删除这笔" @click="removeBill(b)">×</button>
        </li>
      </ul>
      <div v-else class="naw-empty">还没有记账</div>
    </section>

    <!-- ==================== 底部左：趋势 ==================== -->
    <section class="cell cell--vr trend">
      <div class="trend-head">
        <span class="naw-label">近 6 个月支出</span>
        <span class="sse" :class="{ 'sse--on': sseOn }">
          {{ sseOn ? '实时同步中' : '未连接' }}
        </span>
      </div>
      <div v-if="trendPath" class="chart">
        <svg viewBox="0 0 100 100" preserveAspectRatio="none">
          <path :d="trendPath.area" fill="rgba(28,25,23,.05)" />
          <path :d="trendPath.line" fill="none" stroke="#1C1917" stroke-width="0.8" />
        </svg>
        <div class="axis">
          <span v-for="p in trendPath.points" :key="p.period" class="mono">
            {{ p.period.slice(5) }}
          </span>
        </div>
      </div>
      <div v-else class="naw-empty">数据还不够画趋势</div>
    </section>

    <!-- ==================== 底部右：预算 ==================== -->
    <section class="cell budget">
      <span class="naw-label">本月预算</span>
      <template v-if="bookBudget">
        <div class="bud-num">
          <b class="naw-num">{{ money(bookBudget.remaining) }}</b>
          <em>还剩 / 共 {{ money(bookBudget.amount) }}</em>
        </div>
        <div class="bud-bar">
          <i
            :style="{ width: Math.min(Number(bookBudget.usedPercent), 100) + '%' }"
            :class="{ 'bud-bar--over': Number(bookBudget.usedPercent) > 100 }"
          />
        </div>
        <div class="bud-meta">
          <span>已用 <b class="naw-num">{{ money(bookBudget.used) }}</b></span>
          <span>{{ Number(bookBudget.usedPercent).toFixed(0) }}%</span>
          <button class="naw-textbtn" @click="openBudgetEdit(bookBudget)">改</button>
        </div>
        <p v-if="bookBudget.status === 'EXCEEDED'" class="bud-warn">
          已超支，下半月收一收。
        </p>
        <p v-else-if="bookBudget.status === 'WARNING'" class="bud-warn">快到上限了。</p>
      </template>
      <div v-else class="bud-empty">
        <p>还没设预算。</p>
        <router-link :to="{ name: 'budget' }" class="link">去设一个 →</router-link>
      </div>
    </section>

    <!-- 预算编辑弹窗：与预算页共用同一组件，改完只刷新预算，不重跑整页接口 -->
    <BudgetEditDialog
      v-model="budgetDialogOpen"
      :target="budgetDialogTarget"
      @saved="refreshBudgets"
    />
  </div>
</template>

<style scoped>
/* ============================================================================
 * 布局：B 的容量 + A 的划分方式（细线切栏，不用卡片）
 * ========================================================================== */
.desk {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 420px 1fr 268px;
  grid-template-rows: 112px 168px 1fr;
  padding: 0 var(--naw-page-x);
  overflow: hidden;
}

.cell {
  padding: 16px 20px;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.cell--vr {
  border-right: 1px solid var(--naw-rule);
}

.cell--hb {
  border-bottom: 1px solid var(--naw-rule);
}

/* ==================== ① 待确认 ==================== */
.draft {
  grid-column: 1;
  grid-row: 1 / span 3;
  border-right: 1px solid var(--naw-rule);
  border-left: 2px solid var(--naw-seal);
  background: var(--naw-paper-2);
  display: flex;
  flex-direction: column;
  padding: 16px 18px 14px;
}

.draft-head {
  flex-shrink: 0;
}

.cnt {
  color: var(--naw-seal);
  font-family: var(--naw-mono);
  font-size: var(--naw-fs-xs);
}

.rows {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-gutter: stable;
  margin-top: 10px;
}

.row {
  display: grid;
  grid-template-columns: 14px 1fr;
  gap: 9px;
  padding: 11px 0;
  border-bottom: 1px solid var(--naw-rule);
}

.row:last-child {
  border-bottom: none;
}

.row--off {
  opacity: 0.45;
}

.row--low {
  background: var(--naw-seal-soft);
  margin: 0 -8px;
  padding-left: 8px;
  padding-right: 8px;
}

.row-check {
  width: 13px;
  height: 13px;
  margin-top: 4px;
  accent-color: var(--naw-ink);
  cursor: pointer;
}

.row-main {
  min-width: 0;
}

.row-top {
  display: grid;
  grid-template-columns: 1fr 92px;
  gap: 8px;
  align-items: baseline;
}

.row-remark {
  border: none;
  border-bottom: 1px solid transparent;
  background: transparent;
  font-size: var(--naw-fs-md);
  color: var(--naw-ink);
  padding: 1px 0;
  outline: none;
  min-width: 0;
}

.row-remark:hover,
.row-remark:focus {
  border-bottom-color: var(--naw-rule-2);
}

.row-amount {
  text-align: right;
  border: 1px solid transparent;
  background: transparent;
  font-size: var(--naw-fs-lg);
  color: var(--naw-ink);
  padding: 1px 3px;
  outline: none;
  width: 100%;
}

.row-amount:hover,
.row-amount:focus {
  border-color: var(--naw-rule-2);
}

.row-meta {
  display: flex;
  gap: 6px;
  margin-top: 6px;
}

.mini {
  flex: 1;
  min-width: 0;
  border: 1px solid var(--naw-rule);
  border-radius: 2px;
  background: transparent;
  padding: 3px 5px;
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-2);
  outline: none;
}

.mini--err {
  border-color: var(--naw-seal);
  color: var(--naw-seal);
  background: rgba(179, 58, 43, 0.05);
}

.row-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 5px;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

.low {
  color: var(--naw-seal);
}

.hint {
  color: var(--naw-ink-2);
}

/* 合计与操作 */
.draft-foot {
  flex-shrink: 0;
  margin-top: 10px;
  padding-top: 12px;
  border-top: 1.5px solid var(--naw-ink);
  display: flex;
  align-items: center;
  gap: 12px;
}

.totals {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.totals .t {
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

.totals b {
  font-size: var(--naw-fs-lg);
  font-weight: 400;
  color: var(--naw-ink);
  margin-left: 5px;
}

.acts {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 14px;
}

.sealbtn {
  display: flex;
  align-items: center;
  gap: 8px;
  background: none;
  border: none;
  padding: 0;
  cursor: pointer;
  font-family: var(--naw-serif);
  font-size: var(--naw-fs-base);
  color: var(--naw-ink);
  letter-spacing: 0.04em;
}

.sealbtn:disabled {
  cursor: not-allowed;
  color: var(--naw-ink-3);
}

.seal {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 2px solid var(--naw-seal);
  border-radius: 3px;
  color: var(--naw-seal);
  font-family: var(--naw-serif);
  font-size: 18px;
  line-height: 1;
  transform: rotate(-7deg);
  box-shadow: inset 0 0 0 1px var(--naw-seal-soft);
}

.sealbtn:disabled .seal {
  border-color: var(--naw-ink-4);
  color: var(--naw-ink-4);
  box-shadow: none;
}

/* 空状态 */
.empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 10px;
}

.empty-tip {
  margin: 0;
  font-family: var(--naw-serif);
  font-size: var(--naw-fs-md);
  line-height: 1.9;
  color: var(--naw-ink-2);
}

.empty-act {
  margin: 0;
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}

.parse-err {
  flex-shrink: 0;
  padding: 8px 0 10px;
  border-bottom: 1px solid var(--naw-rule);
  margin-bottom: 8px;
}

.err-text {
  margin: 0 0 4px;
  font-size: var(--naw-fs-sm);
  line-height: 1.7;
  color: var(--naw-seal);
}

.parse-err .link {
  font-size: var(--naw-fs-xs);
}

.link {
  color: var(--naw-seal);
  border-bottom: 1px solid var(--naw-seal);
}

/* ==================== ② 本月 ==================== */
.month {
  grid-column: 2 / span 2;
  grid-row: 1;
}

.month-big {
  display: flex;
  align-items: baseline;
  gap: 36px;
  margin-top: 10px;
}

.month-big em {
  display: block;
  font-style: normal;
  font-size: var(--naw-fs-xs);
  letter-spacing: 0.1em;
  color: var(--naw-ink-3);
  margin-bottom: 1px;
}

.month-big span {
  font-size: var(--naw-fs-3xl);
  line-height: 1.1;
}

.month-big .net {
  margin-left: 30px;
  padding-left: 30px;
  border-left: 1px solid var(--naw-rule);
}

.month-big .net span {
  font-size: var(--naw-fs-xl);
  color: var(--naw-ink-2);
}

/* ==================== ③ 分类占比 ==================== */
.share {
  grid-column: 2;
  grid-row: 2;
}

.track {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin-top: 10px;
}

.bar {
  display: grid;
  grid-template-columns: 48px 1fr 76px 44px;
  align-items: center;
  gap: 10px;
  font-size: var(--naw-fs-sm);
}

.bar .k {
  color: var(--naw-ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bar .g {
  height: 4px;
  background: var(--naw-rule);
}

.bar .g i {
  display: block;
  height: 100%;
  background: var(--naw-ink-2);
}

.bar .v {
  text-align: right;
  font-size: var(--naw-fs-base);
}

.bar .p {
  text-align: right;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

/* ==================== ④ 最近 ==================== */
.recent {
  grid-column: 3;
  grid-row: 2;
}

.recent-list {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
}

.recent-row {
  display: grid;
  grid-template-columns: 1fr auto 14px;
  align-items: baseline;
  gap: 8px;
  padding: 5px 0;
  border-bottom: 1px solid var(--naw-rule);
  font-size: var(--naw-fs-sm);
}

.recent-row:last-child {
  border-bottom: none;
}

.recent-row .nm {
  color: var(--naw-ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-row .am {
  font-size: var(--naw-fs-base);
}

.del {
  background: none;
  border: none;
  padding: 0;
  color: var(--naw-ink-4);
  cursor: pointer;
  font-size: 13px;
  line-height: 1;
}

.del:hover {
  color: var(--naw-seal);
}

/* ==================== ⑤ 趋势 ==================== */
.trend {
  grid-column: 2;
  grid-row: 3;
  display: flex;
  flex-direction: column;
}

.trend-head {
  display: flex;
  align-items: baseline;
}

.sse {
  margin-left: auto;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-4);
  display: flex;
  align-items: center;
  gap: 5px;
}

.sse::before {
  content: '';
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--naw-ink-4);
}

.sse--on {
  color: var(--naw-ink-3);
}

.sse--on::before {
  background: var(--naw-income);
}

.chart {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  margin-top: 10px;
}

.chart svg {
  display: block;
  width: 100%;
  flex: 1;
  min-height: 0;
}

.axis {
  display: flex;
  justify-content: space-between;
  margin-top: 5px;
}

.axis span {
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

/* ==================== ⑥ 预算 ==================== */
.budget {
  grid-column: 3;
  grid-row: 3;
  display: flex;
  flex-direction: column;
}

.bud-num {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 10px;
}

.bud-num b {
  font-size: var(--naw-fs-2xl);
  font-weight: 400;
}

.bud-num em {
  font-style: normal;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

.bud-bar {
  margin-top: 10px;
  height: 5px;
  background: var(--naw-rule);
}

.bud-bar i {
  display: block;
  height: 100%;
  background: var(--naw-ink);
}

.bud-bar i.bud-bar--over {
  background: var(--naw-seal);
}

.bud-meta {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

.bud-meta b {
  color: var(--naw-ink-2);
  font-weight: 400;
}

.bud-warn {
  margin: 12px 0 0;
  padding-top: 10px;
  border-top: 1px solid var(--naw-rule);
  font-size: var(--naw-fs-sm);
  color: var(--naw-seal);
  line-height: 1.6;
}

.bud-empty {
  margin-top: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}

.bud-empty p {
  margin: 0;
}

/* ============================================================================
 * 输入区：贴在 AI 对话栏底部
 *
 * ★ 为什么用白底 + 深一档描边：
 *   底部那条全宽输入带被用户反馈"找不到"——它太窄、描边几乎看不见、占位符颜色也淡。
 *   控制件在这套语言里本来就是"1px 描边 + 透明底"，但输入框需要被一眼认出来，
 *   所以给它**唯一一处纯白底**（纸是 #FBF9F4，白是 #FFFFFF，差一点点就够区分），
 *   描边从 rule(13%) 提到 rule-3(50%)，占位符从 ink-4 提到 ink-3。
 *   这不是加卡片，是把"这里可以打字"讲清楚。
 * ========================================================================== */
.ask {
  flex-shrink: 0;
  padding-top: 12px;
  border-top: 1px solid var(--naw-rule-2);
}

.ask-label {
  display: block;
  font-size: var(--naw-fs-xs);
  letter-spacing: 0.12em;
  color: var(--naw-ink-4);
  margin-bottom: 6px;
}

.ask-box {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 5px 5px 10px;
  background: #fff;
  border: 1px solid var(--naw-rule-3);
  border-radius: 2px;
  transition: border-color 0.14s;
}

.ask-box:focus-within {
  border-color: var(--naw-ink);
}

.ask-input {
  flex: 1;
  min-width: 0;
  border: none;
  background: transparent;
  font-size: var(--naw-fs-md);
  color: var(--naw-ink);
  outline: none;
  padding: 3px 0;
}

/* ★ 占位符降到最淡一档（ink-4）：
 *   之前为了"显眼"提到 ink-3，结果示例句看起来像已经输入的内容，
 *   用户会以为输入框里已经有字了。示例只是提示，不该有正文的分量。 */
.ask-input::placeholder {
  color: var(--naw-ink-4);
}

.ask-go {
  flex-shrink: 0;
  background: var(--naw-ink);
  color: var(--naw-paper);
  border: none;
  border-radius: 3px;
  padding: 6px 14px;
  font-size: var(--naw-fs-base);
  cursor: pointer;
  transition: opacity 0.14s;
}

.ask-go:hover {
  opacity: 0.86;
}

.ask-go:disabled {
  opacity: 0.25;
  cursor: not-allowed;
}

.ask-hint,
.ask-parsing {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-4);
}

.ask-parsing {
  color: var(--naw-ink-3);
}
</style>
