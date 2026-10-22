<script setup lang="ts">
/**
 * 预算页。
 *
 * ★ 预算**只做月度**，一个账本一个月一条（后端就是这样设计的，没有 period_type）。
 * ★ `categoryId = null` 表示**账本级预算**（管整个账本），有值才是分类级。
 * ★ `remaining` 可能为**负数**（超支），不能当"剩余额"直接显示成正常数字。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  budgetCalendar,
  budgetStatus,
  deleteBudget,
} from '../api/bill'
import { treeCategories } from '../api/base'
import { BusinessError } from '../api/request'
import { useBookStore } from '../stores'
import type { BudgetCalendarVO, BudgetStatusVO, CategoryTreeVO } from '../api/types'
import BudgetEditDialog from '../components/BudgetEditDialog.vue'

const bookStore = useBookStore()

const list = ref<BudgetStatusVO[]>([])
const expenseTree = ref<CategoryTreeVO[]>([])
const loading = ref(true)

/** 日历（选中某个预算才拉） */
const activeId = ref<number | null>(null)
const calendar = ref<BudgetCalendarVO | null>(null)

const monthStr = computed(() => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
})

async function load() {
  if (!bookStore.currentId) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const [bs, tree] = await Promise.allSettled([
      budgetStatus({ bookId: bookStore.currentId }),
      treeCategories('EXPENSE'),
    ])
    if (bs.status === 'fulfilled') {
      list.value = bs.value
      if (!activeId.value && list.value.length) activeId.value = list.value[0].budgetId
    }
    if (tree.status === 'fulfilled') expenseTree.value = tree.value
    if (activeId.value) await loadCalendar()
  } finally {
    loading.value = false
  }
}

async function loadCalendar() {
  if (!activeId.value) return
  try {
    calendar.value = await budgetCalendar(activeId.value, monthStr.value)
  } catch {
    calendar.value = null
  }
}

onMounted(load)

// ============================================================================
// 新增 / 编辑
// ============================================================================

/**
 * 预算编辑弹窗：dialogTarget 为 null 表示新建，传入预算条目表示修改。
 * 表单与保存逻辑都在 BudgetEditDialog 组件里，这里只负责开关与刷新。
 */
const dialogOpen = ref(false)
const dialogTarget = ref<BudgetStatusVO | null>(null)

function openCreate() {
  dialogTarget.value = null
  dialogOpen.value = true
}

function openEdit(b: BudgetStatusVO) {
  dialogTarget.value = b
  dialogOpen.value = true
}

/** 弹窗保存成功后刷新列表与日历 */
async function onDialogSaved() {
  await load()
}

async function remove(b: BudgetStatusVO) {
  try {
    await ElMessageBox.confirm(
      '删除预算不会删除已发生的账单，只是不再追踪这条预算。',
      `删除「${b.categoryName || '账本预算'}」？`,
      { confirmButtonText: '删除', cancelButtonText: '算了', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteBudget(b.budgetId)
    ElMessage.success('已删除')
    if (activeId.value === b.budgetId) activeId.value = null
    await load()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '删除失败')
  }
}

// ============================================================================
// 展示
// ============================================================================

function money(s: string | number | null | undefined) {
  return Number(s ?? 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

const bookBudget = computed(() => list.value.find((b) => b.categoryId == null) ?? null)
const catBudgets = computed(() => list.value.filter((b) => b.categoryId != null))

const activeBudget = computed(() => list.value.find((b) => b.budgetId === activeId.value) ?? null)

/** 日历按 7 列铺，前面补空位 */
const calendarCells = computed(() => {
  const days = calendar.value?.days ?? []
  if (!days.length) return []
  const first = new Date(`${days[0].date}T00:00:00`)
  // 周一为一周开始
  const pad = (first.getDay() + 6) % 7
  return [
    ...Array.from({ length: pad }, () => null),
    ...days.map((d) => ({ ...d, day: Number(d.date.slice(8, 10)) })),
  ]
})

/** 单日金额的最大值，用来定色阶 */
const maxDay = computed(() => Math.max(...(calendar.value?.days ?? []).map((d) => Number(d.amount)), 1))

function dayAlpha(amount: string) {
  const v = Number(amount)
  if (!v) return 0.05
  return Math.min(0.72, 0.12 + (v / maxDay.value) * 0.6)
}

function pick(id: number) {
  activeId.value = id
  loadCalendar()
}

const statusText = (s: string) =>
  s === 'EXCEEDED' ? '超支' : s === 'WARNING' ? '接近上限' : '正常'
</script>

<template>
  <div class="budget">
    <!-- ==================== 左：预算列表 ==================== -->
    <section class="left">
      <div class="left-head">
        <span class="naw-label">月度预算 <span class="naw-label__sub">{{ monthStr }}</span></span>
        <button class="naw-textbtn add" @click="openCreate">+ 新建预算</button>
      </div>

      <div v-if="loading" class="naw-empty"><span class="naw-dots"><i /><i /><i /></span></div>
      <div v-else-if="!list.length" class="empty">
        <p>还没有设预算。</p>
        <p class="sub">设一个总预算，就知道这个月还剩多少能花。</p>
        <button class="naw-btn" @click="openCreate">设一个</button>
      </div>

      <div v-else class="list">
        <!-- 账本级 -->
        <div v-if="bookBudget" class="blk">
          <span class="naw-label">账本总预算</span>
          <div
            :class="['card', { 'card--on': activeId === bookBudget.budgetId }]"
            @click="pick(bookBudget.budgetId)"
          >
            <div class="card-top">
              <b class="naw-num">{{ money(bookBudget.remaining) }}</b>
              <span class="card-of">/ {{ money(bookBudget.amount) }}</span>
              <span :class="['st', `st--${bookBudget.status}`]">{{ statusText(bookBudget.status) }}</span>
            </div>
            <div class="bar">
              <i
                :style="{ width: Math.min(Number(bookBudget.usedPercent), 100) + '%' }"
                :class="{ over: bookBudget.status === 'EXCEEDED' }"
              />
            </div>
            <div class="card-meta">
              <span>已用 <b class="naw-num">{{ money(bookBudget.used) }}</b></span>
              <span>{{ Number(bookBudget.usedPercent).toFixed(0) }}%</span>
            </div>
            <div class="card-acts">
              <button class="naw-textbtn" @click.stop="openEdit(bookBudget)">改</button>
              <button class="naw-textbtn naw-textbtn--seal" @click.stop="remove(bookBudget)">删</button>
            </div>
          </div>
        </div>

        <!-- 分类级 -->
        <div v-if="catBudgets.length" class="blk">
          <span class="naw-label">分类预算</span>
          <div
            v-for="b in catBudgets"
            :key="b.budgetId"
            :class="['card', 'card--sm', { 'card--on': activeId === b.budgetId }]"
            @click="pick(b.budgetId)"
          >
            <div class="card-top">
              <span class="card-name">{{ b.categoryName || '未命名' }}</span>
              <b class="naw-num">{{ money(b.used) }}</b>
              <span class="card-of">/ {{ money(b.amount) }}</span>
              <span :class="['st', `st--${b.status}`]">{{ Number(b.usedPercent).toFixed(0) }}%</span>
            </div>
            <div class="bar bar--thin">
              <i
                :style="{ width: Math.min(Number(b.usedPercent), 100) + '%' }"
                :class="{ over: b.status === 'EXCEEDED' }"
              />
            </div>
            <div class="card-acts">
              <button class="naw-textbtn" @click.stop="openEdit(b)">改</button>
              <button class="naw-textbtn naw-textbtn--seal" @click.stop="remove(b)">删</button>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ==================== 右：日历 + 明细 ==================== -->
    <aside class="right">
      <template v-if="activeBudget">
        <div class="right-head">
          <span class="naw-label">
            {{ activeBudget.categoryName || '账本预算' }} · {{ monthStr }}
          </span>
        </div>

        <div class="nums">
          <div>
            <em>预算</em>
            <b class="naw-num">{{ money(activeBudget.amount) }}</b>
          </div>
          <div>
            <em>已用</em>
            <b class="naw-num">{{ money(activeBudget.used) }}</b>
          </div>
          <div>
            <em>{{ Number(activeBudget.remaining) < 0 ? '超支' : '剩余' }}</em>
            <b
              :class="['naw-num', { 'naw-num--seal': Number(activeBudget.remaining) < 0 }]"
            >{{ money(Math.abs(Number(activeBudget.remaining))) }}</b>
          </div>
        </div>

        <!-- 日历 -->
        <div class="cal">
          <div class="cal-head">
            <span v-for="w in ['一', '二', '三', '四', '五', '六', '日']" :key="w">{{ w }}</span>
          </div>
          <div class="cal-grid">
            <template v-for="(c, i) in calendarCells" :key="i">
              <span v-if="!c" class="cal-cell cal-cell--pad" />
              <span
                v-else
                class="cal-cell"
                :class="{ 'cal-cell--today': c.date === new Date().toISOString().slice(0, 10) }"
                :style="{ background: `rgba(28,25,23,${dayAlpha(c.amount)})` }"
                :title="`${c.date} ${money(c.amount)}`"
              >
                <span class="cal-day">{{ c.day }}</span>
              </span>
            </template>
          </div>
          <div v-if="calendar" class="cal-foot">
            <span>已用 <b class="naw-num">{{ money(calendar.used) }}</b></span>
            <span>剩 <b class="naw-num">{{ money(calendar.remaining) }}</b></span>
            <div class="legend">
              <i style="background: rgba(28,25,23,.05)" />少
              <i style="background: rgba(28,25,23,.72)" />多
            </div>
          </div>
        </div>

        <div class="right-foot">
          <span class="note">提醒阈值 {{ activeBudget.alertThreshold }}%</span>
        </div>
      </template>

      <div v-else class="right-empty">
        <p>选左边一条预算</p>
        <p class="sub">这里显示它的日历和每日花费。</p>
      </div>
    </aside>
  </div>

  <!-- ==================== 弹层 ==================== -->
  <BudgetEditDialog v-model="dialogOpen" :target="dialogTarget" @saved="onDialogSaved" />
</template>

<style scoped>
.budget {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 1fr 340px;
  padding: 0 var(--naw-page-x);
  overflow: hidden;
}

/* ==================== 左 ==================== */
.left {
  padding: 16px 26px 0 0;
  border-right: 1px solid var(--naw-rule);
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.left-head {
  display: flex;
  align-items: baseline;
  padding-bottom: 10px;
  border-bottom: 1.5px solid var(--naw-ink);
  flex-shrink: 0;
}

.add {
  margin-left: auto;
  font-size: var(--naw-fs-sm);
}

.empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: flex-start;
  gap: 10px;
  color: var(--naw-ink-3);
}

.empty p {
  margin: 0;
  font-size: var(--naw-fs-base);
}

.empty .sub {
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-4);
}

.list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-gutter: stable;
  padding-bottom: 16px;
}

.blk {
  padding: 14px 0 0;
}

.card {
  margin-top: 8px;
  padding: 12px 14px;
  border: 1px solid var(--naw-rule);
  border-radius: 2px;
  cursor: pointer;
  transition: border-color 0.14s;
}

.card:hover {
  border-color: var(--naw-rule-2);
}

/* ★ 选中：朱红左边线，与全站一致 */
.card--on {
  border-color: var(--naw-rule-2);
  box-shadow: inset 3px 0 0 var(--naw-seal);
  background: var(--naw-paper-2);
}

.card--sm {
  padding: 9px 12px;
}

.card-top {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.card-top b {
  font-size: var(--naw-fs-2xl);
  font-weight: 400;
  color: var(--naw-ink);
}

.card--sm .card-top b {
  font-size: var(--naw-fs-lg);
  margin-left: auto;
}

.card-name {
  font-size: var(--naw-fs-base);
  color: var(--naw-ink);
}

.card-of {
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}

.st {
  margin-left: auto;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

.card--sm .st {
  margin-left: 0;
  font-family: var(--naw-mono);
}

.st--WARNING {
  color: var(--naw-ink-2);
}

.st--EXCEEDED {
  color: var(--naw-seal);
}

.bar {
  margin-top: 9px;
  height: 5px;
  background: var(--naw-rule);
}

.bar--thin {
  height: 3px;
  margin-top: 6px;
}

.bar i {
  display: block;
  height: 100%;
  background: var(--naw-ink);
}

.bar i.over {
  background: var(--naw-seal);
}

.card-meta {
  display: flex;
  justify-content: space-between;
  margin-top: 7px;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

.card-meta b {
  color: var(--naw-ink-2);
  font-weight: 400;
}

.card-acts {
  display: flex;
  gap: 12px;
  margin-top: 8px;
  opacity: 0;
  transition: opacity 0.14s;
}

.card:hover .card-acts {
  opacity: 1;
}

.card-acts .naw-textbtn {
  font-size: var(--naw-fs-xs);
}

/* ==================== 右 ==================== */
.right {
  padding: 16px 0 0 24px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.right-head {
  padding-bottom: 10px;
  border-bottom: 1.5px solid var(--naw-ink);
  flex-shrink: 0;
}

.nums {
  display: flex;
  gap: 26px;
  padding: 14px 0;
  border-bottom: 1px solid var(--naw-rule);
}

.nums em {
  display: block;
  font-style: normal;
  font-size: var(--naw-fs-xs);
  letter-spacing: 0.1em;
  color: var(--naw-ink-3);
  margin-bottom: 1px;
}

.nums b {
  font-size: var(--naw-fs-xl);
  font-weight: 400;
}

.cal {
  padding: 14px 0;
}

.cal-head {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 3px;
  margin-bottom: 4px;
}

.cal-head span {
  text-align: center;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

.cal-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 3px;
}

.cal-cell {
  aspect-ratio: 1;
  border-radius: 2px;
  display: grid;
  place-items: center;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink);
}

.cal-cell--pad {
  background: transparent;
}

.cal-cell--today {
  outline: 1.5px solid var(--naw-ink);
  outline-offset: -1px;
}

.cal-day {
  opacity: 0.65;
}

.cal-foot {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--naw-rule);
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

.cal-foot b {
  color: var(--naw-ink-2);
  font-weight: 400;
  margin-left: 4px;
}

.legend {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 4px;
}

.legend i {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 1px;
}

.right-foot {
  margin-top: auto;
  padding: 10px 0 14px;
  border-top: 1px solid var(--naw-rule);
}

.note {
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-4);
}

.right-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 6px;
  color: var(--naw-ink-3);
  font-size: var(--naw-fs-base);
}

.right-empty p {
  margin: 0;
}

.right-empty .sub {
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-4);
}

/* ★ 弹层样式（.mask / .dialog / .df …）已随表单一起搬到 components/BudgetEditDialog.vue */
</style>
