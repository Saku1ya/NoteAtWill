<script setup lang="ts">
/**
 * 账单页：三栏「筛选 / 流水 / 详情」。
 *
 * ★ 这一页承担「AI 记漏了、记错了」的兜底：
 *   所以必须有**手动新增**入口，而且改单要能改金额、分类、账户、时间、备注。
 *
 * ★★ 一个必须遵守的接口契约（踩过）：
 *   `PUT /api/bills/{id}` 是**整体替换**语义。
 *   只提交改动过的字段会让 `accountId` 变成 undefined → 后端按"有意解除账户关联"处理，
 *   用户的账户关联就被悄悄清掉了。所以这里**永远提交完整对象**。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pageBills, getBill, createBill, updateBill, deleteBill } from '../api/bill'
import { treeCategories, listAccounts } from '../api/base'
import { BusinessError } from '../api/request'
import { useRoute, useRouter } from 'vue-router'
import { useBookStore } from '../stores'
import CategorySelect from '../components/CategorySelect.vue'
import DateTimePicker from '../components/DateTimePicker.vue'
import type { AccountVO, BillQuery, BillRequest, BillVO, CategoryTreeVO } from '../api/types'

const bookStore = useBookStore()
const route = useRoute()
const router = useRouter()

// ============================================================================
// 数据
// ============================================================================

const rows = ref<BillVO[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)

const expenseTree = ref<CategoryTreeVO[]>([])
const incomeTree = ref<CategoryTreeVO[]>([])
const accounts = ref<AccountVO[]>([])

/** 筛选条件 */
const filter = reactive<{
  type: '' | 'EXPENSE' | 'INCOME'
  categoryId: number | null
  accountId: number | null
  keyword: string
  range: [string, string] | null
}>({
  type: '',
  categoryId: null,
  accountId: null,
  keyword: '',
  range: null,
})

/** 当前选中（右侧详情） */
const selected = ref<BillVO | null>(null)

/** 当前编辑中的副本（改了没保存时与原对象不同） */
const editing = ref<BillVO | null>(null)
const saving = ref(false)

const detailTree = computed(() => (editing.value?.type === 'INCOME' ? incomeTree.value : expenseTree.value))

// ============================================================================
// 加载
// ============================================================================

async function load() {
  if (!bookStore.currentId) return
  loading.value = true
  try {
    const q: BillQuery = {
      bookId: bookStore.currentId,
      page: page.value,
      size,
      type: filter.type || null,
      categoryId: filter.categoryId,
      accountId: filter.accountId,
      keyword: filter.keyword.trim() || null,
      startTime: filter.range?.[0] ? `${filter.range[0]} 00:00:00` : null,
      endTime: filter.range?.[1] ? `${filter.range[1]} 23:59:59` : null,
    }
    const p = await pageBills(q)
    rows.value = p.records
    total.value = p.total
    // 保持选中项：列表刷新后重新指向新对象，否则详情显示旧值
    if (selected.value) {
      const again = rows.value.find((r) => r.id === selected.value!.id)
      if (again) select(again)
      else clearSelection()
    }
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function loadBasics() {
  const [exp, inc, acc] = await Promise.allSettled([
    treeCategories('EXPENSE'),
    treeCategories('INCOME'),
    listAccounts(),
  ])
  if (exp.status === 'fulfilled') expenseTree.value = exp.value
  if (inc.status === 'fulfilled') incomeTree.value = inc.value
  if (acc.status === 'fulfilled') accounts.value = acc.value
}

onMounted(async () => {
  await Promise.all([loadBasics(), load()])
  await selectFromQuery()
})

/**
 * 支持 `?bill=<id>` 进入：从对话流卡片点「去账单页修改」跳过来时，
 * 自动选中并展开详情，不用用户再到列表里找一遍。
 *
 * ★ 用 `getBill` 直接拉，而不是在列表里找：
 *   那一笔可能不在当前筛选结果 / 当前页里。
 */
async function selectFromQuery() {
  const id = Number(route.query.bill)
  if (!id) return
  try {
    const b = await getBill(id)
    select(b)
    // ★ 清掉 query，避免刷新时又跳一次（用户可能已经看完手动关掉了）
    router.replace({ name: 'bills' })
  } catch {
    // 账单可能已被删除：静默忽略，不影响列表
  }
}

watch(page, load)

function applyFilter() {
  page.value = 1
  load()
}

function resetFilter() {
  filter.type = ''
  filter.categoryId = null
  filter.accountId = null
  filter.keyword = ''
  filter.range = null
  page.value = 1
  load()
}

// ============================================================================
// 选中与编辑
// ============================================================================

function select(b: BillVO) {
  selected.value = b
  editing.value = JSON.parse(JSON.stringify(b))
}

function clearSelection() {
  selected.value = null
  editing.value = null
}

/** 改类型要清分类 —— 两棵树 id 不通用 */
function onDetailTypeChange() {
  if (!editing.value) return
  editing.value.categoryId = 0
}

const dirty = computed(() => {
  if (!selected.value || !editing.value) return false
  const a = selected.value
  const b = editing.value
  return (
    a.amount !== b.amount ||
    a.categoryId !== b.categoryId ||
    a.accountId !== b.accountId ||
    a.billTime !== b.billTime ||
    (a.remark ?? '') !== (b.remark ?? '') ||
    a.type !== b.type
  )
})

async function save() {
  if (!editing.value || !bookStore.currentId) return
  if (!editing.value.categoryId) {
    ElMessage.error('请选择分类')
    return
  }
  saving.value = true
  try {
    // ★ 整体提交：绝不能只发改动字段（accountId 会被当成"解除关联"）
    const payload: BillRequest = {
      bookId: bookStore.currentId,
      categoryId: editing.value.categoryId as number,
      type: editing.value.type,
      amount: editing.value.amount,
      billTime: editing.value.billTime,
      remark: editing.value.remark,
      accountId: editing.value.accountId,
    }
    const updated = await updateBill(editing.value.id, payload)
    ElMessage.success('已保存')
    await load()
    select(updated)
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!selected.value) return
  const b = selected.value
  try {
    await ElMessageBox.confirm(
      '删除后这笔账会从统计和预算里一起消失，对应对话卡片也会删除。',
      `删除「${b.remark || b.categoryName || '这笔账'}」？`,
      { confirmButtonText: '删除', cancelButtonText: '算了', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteBill(b.id)
    ElMessage.success('已删除')
    clearSelection()
    await load()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '删除失败')
  }
}

// ============================================================================
// 新增（手动记账兜底）
// ============================================================================

const createOpen = ref(false)
const creating = ref(false)
const createForm = reactive<{
  type: 'EXPENSE' | 'INCOME'
  amount: string
  categoryId: number | null
  accountId: number | null
  billTime: string
  remark: string
}>({
  type: 'EXPENSE',
  amount: '',
  categoryId: null,
  accountId: null,
  billTime: nowStr(),
  remark: '',
})

function nowStr() {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:00`
}

const createTree = computed(() => (createForm.type === 'INCOME' ? incomeTree.value : expenseTree.value))

function openCreate() {
  createForm.type = 'EXPENSE'
  createForm.amount = ''
  createForm.categoryId = null
  createForm.accountId = null
  createForm.billTime = nowStr()
  createForm.remark = ''
  createOpen.value = true
}

async function doCreate() {
  if (!bookStore.currentId) return
  if (!createForm.categoryId) {
    ElMessage.error('请选择分类')
    return
  }
  const amt = Number(createForm.amount)
  if (!(amt > 0)) {
    ElMessage.error('金额要大于 0')
    return
  }
  creating.value = true
  try {
    await createBill({
      bookId: bookStore.currentId,
      categoryId: createForm.categoryId,
      type: createForm.type,
      amount: createForm.amount,
      billTime: createForm.billTime,
      remark: createForm.remark || null,
      accountId: createForm.accountId,
    })
    ElMessage.success('已记账')
    createOpen.value = false
    page.value = 1
    await load()
  } catch (e) {
    // ★ billTime 不能是未来时间，后端会报 BILL_TIME_FUTURE
    ElMessage.error(e instanceof BusinessError ? e.message : '记账失败')
  } finally {
    creating.value = false
  }
}

// ============================================================================
// 展示
// ============================================================================

/** 按天分组（后端返回已按时间倒序） */
const days = computed(() => {
  const map = new Map<string, BillVO[]>()
  for (const r of rows.value) {
    const d = r.billTime.slice(0, 10)
    if (!map.has(d)) map.set(d, [])
    map.get(d)!.push(r)
  }
  return [...map.entries()].map(([date, list]) => ({
    date,
    list,
    expense: list.filter((x) => x.type === 'EXPENSE').reduce((s, x) => s + Number(x.amount), 0),
  }))
})

const weekOf = (date: string) => {
  const d = new Date(`${date}T00:00:00`)
  return '周' + '日一二三四五六'[d.getDay()]
}

const dayLabel = (date: string) => {
  const [, m, d] = date.split('-')
  return `${Number(m)} 月 ${Number(d)} 日`
}

function money(s: string | number | null | undefined) {
  return Number(s ?? 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

function signed(b: BillVO) {
  return `${b.type === 'INCOME' ? '+' : '−'}${money(b.amount)}`
}

/** 精选条件计数，用于"已筛 N 项" */
const appliedCount = computed(() => {
  let n = 0
  if (filter.type) n++
  if (filter.categoryId) n++
  if (filter.accountId) n++
  if (filter.keyword) n++
  if (filter.range) n++
  return n
})

/** 当前列表总额 */
const listTotal = computed(() =>
  rows.value.reduce((s, r) => s + (r.type === 'INCOME' ? Number(r.amount) : -Number(r.amount)), 0),
)
</script>

<template>
  <div class="bills">
    <!-- ==================== 左：筛选 ==================== -->
    <aside class="filters">
      <div class="fg">
        <span class="naw-label">类型</span>
        <label v-for="t in [{ k: '', l: '全部' }, { k: 'EXPENSE', l: '支出' }, { k: 'INCOME', l: '收入' }]"
               :key="t.k" class="fopt" :class="{ 'fopt--on': filter.type === t.k }">
          <input v-model="filter.type" type="radio" :value="t.k" class="sr" @change="applyFilter" />
          <i class="box" />{{ t.l }}
        </label>
      </div>

      <div class="fg">
        <span class="naw-label">分类</span>
        <label class="fopt" :class="{ 'fopt--on': filter.categoryId === null }">
          <input v-model="filter.categoryId" type="radio" :value="null" class="sr" @change="applyFilter" />
          <i class="box" />全部
        </label>
        <template v-for="p in expenseTree" :key="p.id">
          <label class="fopt" :class="{ 'fopt--on': filter.categoryId === p.id }">
            <input v-model="filter.categoryId" type="radio" :value="p.id" class="sr" @change="applyFilter" />
            <i class="box" />{{ p.name }}
          </label>
        </template>
      </div>

      <div class="fg">
        <span class="naw-label">账户</span>
        <label class="fopt" :class="{ 'fopt--on': filter.accountId === null }">
          <input v-model="filter.accountId" type="radio" :value="null" class="sr" @change="applyFilter" />
          <i class="box" />全部
        </label>
        <label v-for="a in accounts" :key="a.id" class="fopt"
               :class="{ 'fopt--on': filter.accountId === a.id }">
          <input v-model="filter.accountId" type="radio" :value="a.id" class="sr" @change="applyFilter" />
          <i class="box" />{{ a.name }}
        </label>
      </div>

      <div class="fg">
        <span class="naw-label">关键词</span>
        <input
          v-model="filter.keyword"
          class="naw-input"
          placeholder="备注里包含…"
          @keyup.enter="applyFilter"
        />
      </div>

      <div v-if="appliedCount" class="applied">
        已筛 <b>{{ appliedCount }}</b> 项
        <button class="naw-textbtn" @click="resetFilter">清空条件</button>
      </div>
    </aside>

    <!-- ==================== 中：流水 ==================== -->
    <section class="list">
      <div class="list-head">
        <span class="list-title">账单</span>
        <span class="list-meta">共 {{ total }} 笔</span>
        <span class="list-sum">
          合计
          <b :class="['naw-num', { 'naw-num--seal': listTotal > 0 }]">{{ money(listTotal) }}</b>
        </span>
        <button class="naw-textbtn list-add" @click="openCreate">+ 手动记一笔</button>
      </div>

      <div v-if="loading" class="naw-empty"><span class="naw-dots"><i /><i /><i /></span> 加载中</div>
      <div v-else-if="!days.length" class="naw-empty">没有符合条件的账单</div>

      <div v-else class="days">
        <div v-for="d in days" :key="d.date" class="day">
          <div class="day-bar">
            <span class="d">{{ dayLabel(d.date) }}</span>
            <span class="w">{{ weekOf(d.date) }}</span>
            <span class="t">
              {{ d.list.length }} 笔
              <b class="naw-num">−{{ money(d.expense) }}</b>
            </span>
          </div>
          <ul class="rows">
            <li
              v-for="b in d.list"
              :key="b.id"
              :class="['row', { 'row--sel': selected?.id === b.id }]"
              @click="select(b)"
            >
              <span class="tm naw-mono">{{ b.billTime.slice(11, 16) }}</span>
              <span class="tx">
                <b>{{ b.remark || b.categoryName || '未命名' }}</b>
                <span class="src">{{ b.source === 'AI' ? 'AI 解析' : b.source === 'MANUAL' ? '手动记账' : '导入' }}</span>
              </span>
              <span class="cat">{{ b.categoryName || '未分类' }}</span>
              <span :class="['am', 'naw-num', { 'naw-num--income': b.type === 'INCOME' }]">
                {{ signed(b) }}
              </span>
            </li>
          </ul>
        </div>
      </div>

      <div v-if="total > size" class="pager">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="prev, pager, next"
          background
        />
      </div>
    </section>

    <!-- ==================== 右：详情 ==================== -->
    <aside class="detail">
      <template v-if="editing">
        <div class="detail-head">
          <span class="naw-label">这笔账</span>
          <div class="detail-amt">
            <b :class="['naw-num', { 'naw-num--income': editing.type === 'INCOME' }]">
              {{ editing.type === 'INCOME' ? '+' : '−' }}{{ money(editing.amount) }}
            </b>
            <em>{{ editing.type === 'INCOME' ? '收入' : '支出' }} · {{ editing.billTime }}</em>
          </div>
        </div>

        <div class="fields">
          <div class="f">
            <span class="k">备注</span>
            <span class="v"><input v-model="editing.remark" class="naw-input" maxlength="50" /></span>
          </div>

          <div class="f">
            <span class="k">金额</span>
            <span class="v">
              <input v-model="editing.amount" class="naw-input naw-num" inputmode="decimal" />
            </span>
          </div>

          <div class="f">
            <span class="k">类型</span>
            <span class="v">
              <select v-model="editing.type" class="naw-input" @change="onDetailTypeChange">
                <option value="EXPENSE">支出</option>
                <option value="INCOME">收入</option>
              </select>
            </span>
          </div>

          <div class="f">
            <span class="k">分类</span>
            <span class="v">
              <select v-model.number="editing.categoryId" class="naw-input">
                <option :value="0" disabled>请选择</option>
                <optgroup v-for="p in detailTree" :key="p.id" :label="p.name">
                  <option :value="p.id">{{ p.name }}（一级）</option>
                  <option v-for="c in p.children" :key="c.id" :value="c.id">
                    {{ p.name }}/{{ c.name }}
                  </option>
                </optgroup>
              </select>
            </span>
          </div>

          <div class="f">
            <span class="k">账户</span>
            <span class="v">
              <select v-model.number="editing.accountId" class="naw-input">
                <option :value="null">不关联账户</option>
                <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
              </select>
            </span>
          </div>

          <div class="f">
            <span class="k">时间</span>
            <span class="v">
              <DateTimePicker v-model="editing.billTime" />
            </span>
          </div>

          <div class="f">
            <span class="k">来源</span>
            <span class="v src-v">
              {{ editing.source === 'AI' ? 'AI 解析' : editing.source === 'MANUAL' ? '手动记账' : '导入' }}
              <span class="mono">#{{ editing.id }}</span>
            </span>
          </div>
        </div>

        <div class="detail-acts">
          <button class="naw-textbtn naw-textbtn--seal" @click="remove">删除</button>
          <button class="naw-textbtn" @click="clearSelection">关闭</button>
          <button class="sealbtn" :disabled="!dirty || saving" @click="save">
            <span class="seal">改</span>
            <span>{{ saving ? '保存中…' : dirty ? '保存' : '未改动' }}</span>
          </button>
        </div>
      </template>

      <div v-else class="detail-empty">
        <p>选左边任意一笔</p>
        <p class="sub">这里会显示它的全部字段，改完点保存。</p>
      </div>
    </aside>
  </div>

  <!-- ==================== 新增弹层 ==================== -->
  <div v-if="createOpen" class="mask" @click.self="createOpen = false">
    <div class="dialog">
      <div class="dialog-head">
        <span>手动记一笔</span>
        <button class="x" @click="createOpen = false">×</button>
      </div>

      <!-- ★ 分类独立成一块、占满整行：它是记账时最需要停留的字段，
           塞在两个 select 中间会显得次要，也放不下图标网格。 -->
      <div class="dfield-block">
        <span class="k">分类</span>
        <CategorySelect
          v-model="createForm.categoryId"
          :tree="createTree"
          placeholder="选一个分类"
        />
      </div>

      <div class="dfields">
        <label class="df">
          <span class="k">类型</span>
          <select v-model="createForm.type" class="naw-input" @change="createForm.categoryId = null">
            <option value="EXPENSE">支出</option>
            <option value="INCOME">收入</option>
          </select>
        </label>

        <label class="df">
          <span class="k">金额</span>
          <input v-model="createForm.amount" class="naw-input naw-num" inputmode="decimal" placeholder="0.00" />
        </label>

        <label class="df">
          <span class="k">账户</span>
          <select v-model.number="createForm.accountId" class="naw-input">
            <option :value="null">不关联账户</option>
            <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
          </select>
        </label>

        <label class="df">
          <span class="k">时间</span>
          <DateTimePicker v-model="createForm.billTime" />
        </label>

        <label class="df df--full">
          <span class="k">备注</span>
          <input v-model="createForm.remark" class="naw-input" maxlength="50" placeholder="选填" />
        </label>
      </div>

      <div class="dialog-foot">
        <span class="tip">时间不能晚于现在（后端会拒）</span>
        <button class="naw-textbtn" @click="createOpen = false">取消</button>
        <button class="naw-btn" :disabled="creating" @click="doCreate">
          {{ creating ? '保存中…' : '记上' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bills {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 192px 1fr 316px;
  padding: 0 var(--naw-page-x);
  overflow: hidden;
}

/* ==================== 左：筛选 ==================== */
.filters {
  padding: 16px 20px 0 0;
  border-right: 1px solid var(--naw-rule);
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.fg + .fg {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--naw-rule);
}

.fopt {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 4px 0;
  font-size: var(--naw-fs-base);
  color: var(--naw-ink-2);
  cursor: pointer;
}

.fopt:hover {
  color: var(--naw-ink);
}

.fopt--on {
  color: var(--naw-ink);
}

.box {
  width: 10px;
  height: 10px;
  border: 1px solid var(--naw-rule-2);
  flex-shrink: 0;
}

.fopt--on .box {
  background: var(--naw-ink);
  border-color: var(--naw-ink);
}

/* 隐藏原生 radio，保留可访问性 */
.sr {
  position: absolute;
  opacity: 0;
  width: 0;
  height: 0;
}

.applied {
  margin-top: 18px;
  padding-top: 12px;
  border-top: 1px solid var(--naw-rule-2);
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-2);
  display: flex;
  align-items: center;
  gap: 10px;
}

.applied b {
  color: var(--naw-ink);
}

/* ==================== 中：流水 ==================== */
.list {
  padding: 16px 24px 0;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
}

.list-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  padding-bottom: 10px;
  border-bottom: 1.5px solid var(--naw-ink);
  flex-shrink: 0;
}

.list-title {
  font-family: var(--naw-serif);
  font-size: var(--naw-fs-lg);
  letter-spacing: 0.08em;
}

.list-meta {
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}

.list-sum {
  margin-left: auto;
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}

.list-sum b {
  font-size: var(--naw-fs-lg);
  color: var(--naw-ink);
  margin-left: 6px;
}

.list-add {
  margin-left: 16px;
  font-size: var(--naw-fs-sm);
}

.days {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-gutter: stable;
  padding-bottom: 12px;
}

.day + .day {
  margin-top: 12px;
}

.day-bar {
  display: flex;
  align-items: baseline;
  gap: 9px;
  padding: 10px 0 5px;
}

.day-bar .d {
  font-family: var(--naw-serif);
  font-size: var(--naw-fs-md);
  letter-spacing: 0.06em;
}

.day-bar .w {
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

.day-bar .t {
  margin-left: auto;
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}

.day-bar .t b {
  color: var(--naw-ink-2);
  font-weight: 400;
  margin-left: 5px;
}

.rows {
  list-style: none;
  margin: 0;
  padding: 0;
}

.row {
  display: grid;
  grid-template-columns: 44px 1fr 96px 108px;
  align-items: baseline;
  gap: 12px;
  padding: 8px 8px 8px 6px;
  border-bottom: 1px solid var(--naw-rule);
  cursor: pointer;
  transition: background 0.12s;
}

.row:hover {
  background: rgba(28, 25, 23, 0.03);
}

/* ★ 选中：朱红左边线 + 浅纸色，与记账台"待确认"同一套强调 */
.row--sel {
  background: var(--naw-paper-2);
  box-shadow: inset 3px 0 0 var(--naw-seal);
  padding-left: 12px;
}

.row .tm {
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

.row .tx {
  min-width: 0;
}

.row .tx b {
  display: block;
  font-size: var(--naw-fs-md);
  font-weight: 400;
  color: var(--naw-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row .src {
  display: block;
  margin-top: 1px;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-4);
}

.row .cat {
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row .am {
  text-align: right;
  font-size: var(--naw-fs-md);
}

.pager {
  flex-shrink: 0;
  padding: 10px 0;
  border-top: 1px solid var(--naw-rule);
  display: flex;
  justify-content: flex-end;
}

/* ==================== 右：详情 ==================== */
.detail {
  padding: 16px 0 0 24px;
  border-left: 1px solid var(--naw-rule);
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.detail-head {
  padding-bottom: 12px;
  border-bottom: 1.5px solid var(--naw-ink);
  flex-shrink: 0;
}

.detail-amt {
  margin-top: 7px;
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.detail-amt b {
  font-size: var(--naw-fs-3xl);
  font-weight: 400;
}

.detail-amt em {
  font-style: normal;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

.fields {
  margin-top: 12px;
  flex-shrink: 0;
}

.f {
  display: grid;
  grid-template-columns: 52px 1fr;
  align-items: center;
  gap: 10px;
  padding: 7px 0;
  border-bottom: 1px solid var(--naw-rule);
}

.f .k {
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}

.f .v {
  min-width: 0;
}

.f .v .naw-input {
  padding: 3px 7px;
}

.src-v {
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-2);
}

.src-v .mono {
  margin-left: 8px;
  font-family: var(--naw-mono);
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-4);
}

.detail-acts {
  margin-top: auto;
  padding: 14px 0 16px;
  border-top: 1.5px solid var(--naw-ink);
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}

.sealbtn {
  margin-left: auto;
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
}

.sealbtn:disabled {
  cursor: not-allowed;
  color: var(--naw-ink-4);
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

.detail-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 6px;
  color: var(--naw-ink-3);
  font-size: var(--naw-fs-base);
}

.detail-empty p {
  margin: 0;
}

.detail-empty .sub {
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-4);
}

/* ==================== 弹层 ==================== */
.mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(28, 25, 23, 0.28);
  display: grid;
  place-items: center;
}

.dialog {
  width: 560px;
  background: var(--naw-paper);
  border: 1px solid var(--naw-rule-2);
  padding: 20px 22px 18px;
}

.dialog-head {
  display: flex;
  align-items: baseline;
  padding-bottom: 10px;
  border-bottom: 1.5px solid var(--naw-ink);
  font-family: var(--naw-serif);
  font-size: var(--naw-fs-lg);
  letter-spacing: 0.06em;
}

.x {
  margin-left: auto;
  background: none;
  border: none;
  font-size: 18px;
  line-height: 1;
  color: var(--naw-ink-3);
  cursor: pointer;
}

.x:hover {
  color: var(--naw-ink);
}

.dfields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 14px;
}

/* ★ 分类独立整行：它是记账时最需要停留的字段 */
.dfield-block {
  margin-top: 14px;
}

.dfield-block .k {
  display: block;
  font-size: var(--naw-fs-xs);
  letter-spacing: 0.12em;
  color: var(--naw-ink-3);
  margin-bottom: 5px;
}

.df {
  display: block;
}

.df--full {
  grid-column: 1 / -1;
}

.df .k {
  display: block;
  font-size: var(--naw-fs-xs);
  letter-spacing: 0.12em;
  color: var(--naw-ink-3);
  margin-bottom: 5px;
}

.dialog-foot {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 18px;
  padding-top: 12px;
  border-top: 1px solid var(--naw-rule);
}

.dialog-foot .tip {
  margin-right: auto;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}
</style>
