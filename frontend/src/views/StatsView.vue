<script setup lang="ts">
/**
 * 统计页。
 *
 * ★ 数据来源全部是后端的统计接口，**前端不做任何聚合计算** ——
 *   这是项目铁律：统计口径只有一处（后端），前端自己算必然和后端对不上。
 *
 * ★ `aiComment` 的 data 可能是 **null**（AI 不可用），这不是错误。
 *   此时隐藏点评区，不弹错误提示。
 */
import { computed, onMounted, ref } from 'vue'
import { categoryRatio, statsSummary, trend, aiComment, budgetStatus } from '../api/bill'
import { BusinessError } from '../api/request'
import { useBookStore } from '../stores'
import type {
  AiCommentVO,
  BudgetStatusVO,
  CategoryRatioVO,
  StatsSummaryVO,
  TrendVO,
} from '../api/types'

const bookStore = useBookStore()

const overview = ref<StatsSummaryVO | null>(null)
const ratios = ref<CategoryRatioVO[]>([])
const trendVO = ref<TrendVO | null>(null)
const budgets = ref<BudgetStatusVO[]>([])
const loading = ref(true)

// ============================================================================
// 时间区间与粒度
//
// ★★ 为什么不再写死"本月 / 上月"：
//   用户要看半年、一年的时候，横轴固定按"日"会挤成几百个点，根本读不出来。
//   所以：① 区间可选；② 粒度可自己定，也可以交给"自动"按跨度推荐。
//
// ★ 后端 `granularity` 接受 DAY / WEEK / MONTH，不传默认 DAY。
//   三种粒度的 `period` 格式（后端 buildAxis 决定）：
//     DAY   → 2026-10-20
//     WEEK  → 2026-10-19（该周周一的日期）
//     MONTH → 2026-10
// ============================================================================

type Granularity = 'DAY' | 'WEEK' | 'MONTH' | 'AUTO'

/** 区间预设；custom 表示用户手动选了日期 */
const rangePreset = ref<'month' | 'lastMonth' | 'last3' | 'last6' | 'year' | 'custom'>('month')
/** 手动区间（ISO 字符串，date-only） */
const customRange = ref<[string, string] | null>(null)
/** 粒度：自动 / 强制 */
const granularityChoice = ref<Granularity>('AUTO')

function fmtDate(d: Date) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 按预设算出区间 */
function rangeOf(preset: typeof rangePreset.value): { start: string; end: string } {
  const now = new Date()
  const y = now.getFullYear()
  const m = now.getMonth()
  if (preset === 'custom' && customRange.value) {
    return { start: customRange.value[0], end: customRange.value[1] }
  }
  switch (preset) {
    case 'lastMonth':
      return { start: fmtDate(new Date(y, m - 1, 1)), end: fmtDate(new Date(y, m, 0)) }
    case 'last3':
      return { start: fmtDate(new Date(y, m - 2, 1)), end: fmtDate(now) }
    case 'last6':
      return { start: fmtDate(new Date(y, m - 5, 1)), end: fmtDate(now) }
    case 'year':
      return { start: fmtDate(new Date(y, 0, 1)), end: fmtDate(now) }
    default:
      return { start: fmtDate(new Date(y, m, 1)), end: fmtDate(now) }
  }
}

/** 当前生效的区间 */
const activeRange = computed(() => rangeOf(rangePreset.value))

/** 区间跨度（天） */
const spanDays = computed(() => {
  const { start, end } = activeRange.value
  const a = new Date(`${start}T00:00:00`).getTime()
  const b = new Date(`${end}T00:00:00`).getTime()
  return Math.max(1, Math.round((b - a) / 86400000) + 1)
})

/**
 * 实际用的粒度。
 * ★ 自动规则（按"横轴点数落在 8~24 个"反推，超出就换更粗的粒度）：
 *   ≤ 31 天 → DAY；≤ 180 天 → WEEK；更久 → MONTH
 */
const effectiveGranularity = computed<'DAY' | 'WEEK' | 'MONTH'>(() => {
  if (granularityChoice.value !== 'AUTO') return granularityChoice.value
  if (spanDays.value <= 31) return 'DAY'
  if (spanDays.value <= 180) return 'WEEK'
  return 'MONTH'
})

const granularityHint = computed(() => {
  const g = effectiveGranularity.value
  const n = trendVO.value?.points.length ?? 0
  const label = g === 'DAY' ? '按天' : g === 'WEEK' ? '按周' : '按月'
  return granularityChoice.value === 'AUTO'
    ? `自动选中「${label}」（${spanDays.value} 天 → ${n} 个点）`
    : `手动指定「${label}」（${n} 个点）`
})

async function load() {
  const bookId = bookStore.currentId
  if (!bookId) {
    loading.value = false
    return
  }
  loading.value = true
  // ★ 换区间后之前的点评已失效，回到"未生成"状态
  resetComment()
  const { start, end } = activeRange.value

  const [ov, ra, tr, bud] = await Promise.allSettled([
    statsSummary({ bookId, start, end }),
    categoryRatio({ bookId, type: 'EXPENSE', start, end }),
    // ★ 显式传粒度，否则后端默认按天，长区间会挤成几百个点
    trend({ bookId, type: 'EXPENSE', start, end, granularity: effectiveGranularity.value }),
    budgetStatus({ bookId }),
  ])

  if (ov.status === 'fulfilled') overview.value = ov.value
  if (ra.status === 'fulfilled') ratios.value = ra.value
  if (tr.status === 'fulfilled') trendVO.value = tr.value
  if (bud.status === 'fulfilled') budgets.value = bud.value
  loading.value = false
}

/** 换预设 */
function pickPreset(p: typeof rangePreset.value) {
  rangePreset.value = p
  load()
}

/** 手动选区间：Element 的 daterange 返回 [start, end] 字符串数组 */
function onCustomRange(v: [string, string] | null) {
  if (!v || !v[0] || !v[1]) return
  customRange.value = v
  rangePreset.value = 'custom'
  load()
}

/** 换粒度 */
function pickGranularity(g: Granularity) {
  granularityChoice.value = g
  load()
}

onMounted(load)

// ============================================================================
// AI 点评：★ 用户点了才生成（默认不调）
//
// 为什么不自动加载：
//   进统计页就自动请求 → 每次打开都消耗一次大模型调用（token + 十几秒等待），
//   而用户多数时候只是想看数字。所以做成按钮，并且**同一区间只生成一次**，
//   想换一份新的再点"重新生成"。
// ============================================================================

/** 已生成 / 生成中 / 未生成 */
const comment = ref<AiCommentVO | null>(null)
const commentLoading = ref(false)
/** 是否已经尝试过（区分"没点过"和"点了但 AI 不可用"） */
const commentTried = ref(false)

function resetComment() {
  comment.value = null
  commentTried.value = false
  commentLoading.value = false
}

async function generateComment() {
  const bookId = bookStore.currentId
  if (!bookId || commentLoading.value) return
  const { start, end } = activeRange.value
  commentLoading.value = true
  commentTried.value = false
  try {
    comment.value = await aiComment({ bookId, start, end })
    commentTried.value = true
  } catch (e) {
    // ★ AI 不可用不是错误，静默降级
    comment.value = null
    commentTried.value = true
    if (!(e instanceof BusinessError)) console.warn('点评加载失败', e)
  } finally {
    commentLoading.value = false
  }
}

// ============================================================================
// 派生
// ============================================================================

function money(s: string | number | null | undefined) {
  return Number(s ?? 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

const totals = computed(() => {
  const o = overview.value
  const inc = Number(o?.income ?? 0)
  const exp = Number(o?.expense ?? 0)
  return {
    income: inc,
    expense: exp,
    surplus: Number(o?.surplus ?? inc - exp),
    saveRate: inc > 0 ? ((inc - exp) / inc) * 100 : 0,
  }
})

/** 趋势折线路径（手写 SVG，不引图表库） */
const chart = computed(() => {
  const pts = trendVO.value?.points ?? []
  if (pts.length < 2) return null
  const vals = pts.map((p) => Number(p.amount))
  const max = Math.max(...vals, 1)
  const W = 100
  const H = 100
  const step = W / (pts.length - 1)
  const xy = vals.map((v, i) => [i * step, H - (v / max) * (H - 14) - 7] as const)
  const line = `M${xy.map(([x, y]) => `${x.toFixed(2)},${y.toFixed(2)}`).join(' L')}`
  return {
    line,
    area: `${line} L${W},${H} L0,${H} Z`,
    points: pts,
    xy,
    max,
  }
})

/** 分类占比：只取一级分类，最多 4 条 */
/**
 * ★★ 环形图必须画**全部**分类，不能只取前几个：
 *   只画前 4 段的话，剩下分类的钱在环上直接消失，
 *   看得懂的人会发现"各段加起来不到 100%"。
 *   所以环图用全量，右侧列表做"前 N + 其他"的收纳。
 */
const ALL_CATS_LIMIT = 5

/** 环图数据：全量分类（>9 个时把尾部合并成「其他」，避免细碎到看不清） */
const donutCats = computed(() => {
  const all = ratios.value
  if (all.length <= DONUT_COLORS.length) return all
  const head = all.slice(0, DONUT_COLORS.length - 1)
  const tail = all.slice(DONUT_COLORS.length - 1)
  const restAmount = tail.reduce((s, r) => s + Number(r.amount), 0)
  const restPct = tail.reduce((s, r) => s + Number(r.percent), 0)
  return [
    ...head,
    {
      categoryId: -1,
      categoryName: '其他',
      amount: String(restAmount),
      percent: String(restPct),
      children: [],
    } as CategoryRatioVO,
  ]
})

/** 右侧列表：前 N + 其他 */
const topCats = computed(() => {
  const all = ratios.value
  if (all.length <= ALL_CATS_LIMIT) return all
  const head = all.slice(0, ALL_CATS_LIMIT)
  const tail = all.slice(ALL_CATS_LIMIT)
  const restAmount = tail.reduce((s, r) => s + Number(r.amount), 0)
  const restPct = tail.reduce((s, r) => s + Number(r.percent), 0)
  return [
    ...head,
    {
      categoryId: -1,
      categoryName: `其他 ${tail.length} 项`,
      amount: String(restAmount),
      percent: String(restPct),
      children: [],
    } as CategoryRatioVO,
  ]
})
/** 超大额分类（用于标红） */
const exceeded = computed(() => {
  const set = new Set<number>()
  for (const b of budgets.value) {
    if (b.status === 'EXCEEDED' && b.categoryId != null) set.add(b.categoryId)
  }
  return set
})

/**
 * 横轴标签：**抽稀**后按百分比定位。
 *
 * ★ 为什么不能像以前那样"每个点都渲染一个 label"：
 *   一年按天有 365 个点，标签会糊成一团黑。这里按宽度最多放 ~8 个，
 *   其余跳过，并用绝对定位保证首尾对齐。
 *
 * ★ 标签内容随粒度变：
 *   DAY   → 10-20
 *   WEEK  → 10-19（周一）
 *   MONTH → 10月
 */
const axisLabels = computed(() => {
  const pts = trendVO.value?.points ?? []
  if (!pts.length) return []
  const g = effectiveGranularity.value
  // 最多少显示几个，避免拥挤
  const maxLabels = 8
  const step = Math.max(1, Math.ceil(pts.length / maxLabels))
  const out: { key: string; text: string; left: number }[] = []
  for (let i = 0; i < pts.length; i += step) {
    const p = pts[i]
    const text =
      g === 'MONTH'
        ? `${Number(p.period.slice(5, 7))} 月`
        : g === 'WEEK'
          ? p.period.slice(5)
          : p.period.slice(5)
    const left = pts.length === 1 ? 0 : (i / (pts.length - 1)) * 100
    out.push({ key: p.period, text, left })
  }
  // 末点单独补上，保证右边界有值
  const last = pts[pts.length - 1]
  if (out.length && out[out.length - 1].key !== last.period) {
    const text =
      g === 'MONTH' ? `${Number(last.period.slice(5, 7))} 月` : last.period.slice(5)
    out.push({ key: last.period, text, left: 100 })
  }
  return out
})

/**
 * 环形图配色。
 *
 * ★ 不用彩虹色：和「纸墨印」这套语言冲突。
 *   这里用一组"墨的深浅 + 一点朱红与墨绿"——
 *   前几名用深墨，靠后的用浅墨，超支的那一项用朱红。
 *   这样即使在黑白打印或色弱视角下，深浅也能区分。
 */
/**
 * ★★ 必须有**明显的色相差异**，不能只靠明度。
 *   初版用的是「墨的深浅递进」（#1C1917 / #4A443D / #6E675D / #9A948A），
 *   结果第一段是纯黑、第二段是深褐，在 132px 的环上看起来是同一个颜色 ——
 *   用户反馈"不同分类颜色没有区分"。
 *
 *   现在改成：同样压低饱和度（不破坏「纸墨印」的基调），但**色相拉开**：
 *   墨 → 赭 → 靛 → 橄榄 → 绛 → 铜 → 灰绿 → 暖灰
 *   相邻两段色相至少差 40°，在小尺寸下也能一眼分开。
 */
const DONUT_COLORS = [
  '#2B2A28', // 墨
  '#A5643C', // 赭
  '#4A6FA5', // 靛
  '#6B8E4E', // 橄榄
  '#A34A5E', // 绛
  '#C08A2E', // 铜
  '#4F7A78', // 灰绿
  '#8A7A8F', // 淡紫
  '#B3AFA6', // 暖灰（第 9 项之后循环）
]

function donutColor(i: number) {
  return DONUT_COLORS[i % DONUT_COLORS.length]
}

/**
 * 环形图分段。
 *
 * ★ 用 stroke-dasharray 画弧（circle 周长取 100），不引图表库：
 *   首页和统计页都只有一两个图，为它拉进 ECharts（~300KB）不划算。
 *   `offset` 是累计位移，让每段首尾相接。
 */
const donutSegments = computed(() => {
  let acc = 0
  return donutCats.value.map((r, i) => {
    const pct = Number(r.percent) || 0
    const seg = {
      id: r.categoryId,
      pct,
      // dashoffset 向左移，所以取负的累计值
      offset: -acc,
      color: exceeded.value.has(r.categoryId) ? '#B33A2B' : donutColor(i),
    }
    acc += pct
    return seg
  })
})

</script>

<template>
  <div class="stats">
    <!-- ==================== 顶部总览 ==================== -->
    <section class="summary">
      <div class="sum-item">
        <em>支出</em>
        <b class="naw-num">{{ money(totals.expense) }}</b>
      </div>
      <div class="sum-item">
        <em>收入</em>
        <b class="naw-num naw-num--income">{{ money(totals.income) }}</b>
      </div>
      <div class="sum-item">
        <em>结余</em>
        <b class="naw-num">{{ money(totals.surplus) }}</b>
        <span class="delta">储蓄率 {{ totals.saveRate.toFixed(1) }}%</span>
      </div>
    </section>

    <!-- ==================== 区间工具栏（独立一行）
         ★ 原来它塞在总览栏的最右侧，和「支出/收入/结余」挤在一起，
           日期选择器被压成了超宽输入框、还盖住了左边的预设按钮。
           区间是一组"控制件"，把它单独放一行更清楚，也放得下。 -->
    <section class="toolbar">
      <span class="naw-label">区间</span>
      <div class="rs-group">
        <button
          v-for="p in [
            { k: 'month', l: '本月' },
            { k: 'lastMonth', l: '上月' },
            { k: 'last3', l: '近 3 月' },
            { k: 'last6', l: '近半年' },
            { k: 'year', l: '今年' },
          ]"
          :key="p.k"
          :class="['rs', { 'rs--on': rangePreset === p.k }]"
          @click="pickPreset(p.k as typeof rangePreset)"
        >
          {{ p.l }}
        </button>
      </div>

      <el-date-picker
        class="range-picker"
        type="daterange"
        value-format="YYYY-MM-DD"
        format="M月D日"
        range-separator="→"
        start-placeholder="开始"
        end-placeholder="结束"
        :clearable="false"
        :disabled-date="(d: Date) => d.getTime() > Date.now()"
        :model-value="activeRange ? [activeRange.start, activeRange.end] : null"
        @update:model-value="(v: any) => onCustomRange(v)"
      />

      <span class="span-note">{{ spanDays }} 天</span>
    </section>

    <!-- ==================== 左：趋势 + 分类 ==================== -->
    <section class="left">
      <div class="block">
        <div class="trend-head">
          <span class="naw-label">支出趋势</span>
          <!-- 粒度切换：自动 / 按天 / 按周 / 按月 -->
          <div class="gran-switch">
            <button
              v-for="g in [
                { k: 'AUTO', l: '自动' },
                { k: 'DAY', l: '日' },
                { k: 'WEEK', l: '周' },
                { k: 'MONTH', l: '月' },
              ]"
              :key="g.k"
              :class="['gs', { 'gs--on': granularityChoice === g.k }]"
              @click="pickGranularity(g.k as Granularity)"
            >
              {{ g.l }}
            </button>
          </div>
        </div>
        <div v-if="chart" class="chart">
          <svg viewBox="0 0 100 100" preserveAspectRatio="none">
            <path :d="chart.area" fill="rgba(28,25,23,.05)" />
            <path :d="chart.line" fill="none" stroke="#1C1917" stroke-width="0.8" />
          </svg>
          <!-- ★ 横轴只显示抽稀后的标签，否则点数一多就糊成一片 -->
          <div class="axis">
            <span
              v-for="a in axisLabels"
              :key="a.key"
              class="mono"
              :style="{ left: a.left + '%' }"
            >
              {{ a.text }}
            </span>
          </div>
        </div>
        <div v-else class="naw-empty">区间内没有数据</div>
        <div v-if="trendVO" class="chart-foot">
          <span>{{ granularityHint }}</span>
          <span class="foot-total">
            区间合计 <b class="naw-num">{{ money(trendVO.total) }}</b>
          </span>
        </div>
      </div>

      <div class="block block--cats">
        <span class="naw-label">分类占比 <span class="naw-label__sub">按金额排序</span></span>

        <!-- ★ 环形图 + 明细并排：图形给"一眼比例"，列表给"精确数字"，
             两者看的是同一份数据（后端 category-ratio 已按一级汇总）。 -->
        <div v-if="topCats.length" class="cats-wrap">
          <div class="donut">
            <svg viewBox="0 0 42 42" class="donut-svg">
              <circle cx="21" cy="21" r="15.915" fill="none" stroke="var(--naw-rule)" stroke-width="7" />
              <circle
                v-for="seg in donutSegments"
                :key="seg.id"
                cx="21"
                cy="21"
                r="15.915"
                fill="none"
                :stroke="seg.color"
                stroke-width="7"
                :stroke-dasharray="`${seg.pct} ${100 - seg.pct}`"
                :stroke-dashoffset="seg.offset"
                transform="rotate(-90 21 21)"
              />
            </svg>
            <div class="donut-center">
              <b class="naw-num">{{ money(totals.expense) }}</b>
              <em>总支出</em>
            </div>
          </div>

          <div class="cats">
            <div
              v-for="(r, i) in topCats"
              :key="r.categoryId"
              :class="['cat-row', { 'cat-row--over': exceeded.has(r.categoryId) }]"
            >
              <span class="no mono">{{ String(i + 1).padStart(2, '0') }}</span>
              <span class="swatch" :style="{ background: donutColor(i) }" />
              <span class="nm">
                <b>{{ r.categoryName || '未分类' }}</b>
                <span v-if="r.children?.length" class="kid">
                  <template v-for="(c, ci) in r.children.slice(0, 3)" :key="c.categoryId">
                    {{ ci ? ' · ' : '' }}{{ c.categoryName }} {{ money(c.amount) }}
                  </template>
                </span>
              </span>
              <span class="amt naw-num">{{ money(r.amount) }}</span>
              <span class="pct mono">{{ Number(r.percent).toFixed(1) }}%</span>
            </div>
          </div>
        </div>
        <div v-else class="naw-empty">区间内没有支出</div>
      </div>
    </section>

    <!-- ==================== 右：预算 + 点评 ==================== -->
    <aside class="right">
      <div class="block">
        <span class="naw-label">预算执行</span>
        <template v-if="budgets.length">
          <div v-for="b in budgets.slice(0, 4)" :key="b.budgetId" class="bud-row">
            <span class="bn">{{ b.categoryName || b.bookName || '账本预算' }}</span>
            <span class="bv naw-num">{{ money(b.used) }} / {{ money(b.amount) }}</span>
            <span class="bpb">
              <i
                :style="{ width: Math.min(Number(b.usedPercent), 100) + '%' }"
                :class="{ over: b.status === 'EXCEEDED' }"
              />
            </span>
            <span :class="['bp', { 'bp--over': b.status === 'EXCEEDED' }]">
              {{ Number(b.usedPercent).toFixed(0) }}%
            </span>
          </div>
        </template>
        <div v-else class="naw-empty">
          还没设预算
          <router-link :to="{ name: 'budget' }" class="link">去设一个</router-link>
        </div>
      </div>

      <div class="block block--comment">
        <span class="naw-label">AI 点评</span>

        <!-- ★ 默认不生成。点按钮才消耗一次大模型调用。 -->
        <div v-if="commentLoading" class="naw-empty">
          <span class="naw-dots"><i /><i /><i /></span>
          <span class="dloading">我在翻这个月的账，稍等一下</span>
        </div>

        <template v-else-if="comment">
          <p class="comment">{{ comment.comment }}</p>
          <button class="naw-textbtn regen" @click="generateComment">换一份</button>
        </template>

        <!-- 点了但 AI 不可用 -->
        <template v-else-if="commentTried">
          <p class="comment comment--off">
            这个月我还没想好怎么开口。数字都摆在上面了，你先看看。
          </p>
          <button class="naw-textbtn regen" @click="generateComment">再试一次</button>
        </template>

        <!-- 从未点过 -->
        <div v-else class="comment-idle">
          <p class="idle-tip">
            这个月花得怎么样？我可以读一遍所有账单，说点人话给你听。
          </p>
          <p class="idle-note">会调用一次 AI，想要再看随时点。</p>
          <button class="naw-btn idle-btn" @click="generateComment">看看这个月怎么说</button>
        </div>
      </div>

      <div class="right-foot">
        <span class="src-note">数据来自后端统计接口，前端不重复计算</span>
      </div>
    </aside>
  </div>

  <div v-if="loading" class="mask"><span class="naw-dots"><i /><i /><i /></span> 正在统计</div>
</template>

<style scoped>
.stats {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 1fr 320px;
  /* 总览 / 工具栏 / 内容 */
  grid-template-rows: 88px 44px 1fr;
  padding: 0 var(--naw-page-x);
  overflow: hidden;
}

/* ==================== 区间工具栏 ==================== */
.toolbar {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  gap: 16px;
  border-bottom: 1px solid var(--naw-rule);
}

.rs-group {
  display: flex;
  align-items: center;
  gap: 14px;
}

/* 区间预设：下划线式文字按钮，和全站导航/标签页同一套语法 */
.rs {
  background: none;
  border: none;
  padding: 0 0 2px;
  font-family: inherit;
  font-size: var(--naw-fs-base);
  color: var(--naw-ink-3);
  border-bottom: 1px solid transparent;
  cursor: pointer;
  transition:
    color 0.14s,
    border-color 0.14s;
  white-space: nowrap;
}

.rs:hover {
  color: var(--naw-ink-2);
}

/* 选中：墨字 + 朱红下划线（与导航栏选中态同一套） */
.rs--on {
  color: var(--naw-ink);
  border-bottom-color: var(--naw-seal);
}

/* 自定义区间：压成和按钮同高的紧凑控件，不再占满整行 */
.range-picker {
  width: 240px !important;
  flex-shrink: 0;
}

.range-picker :deep(.el-range-input) {
  font-size: var(--naw-fs-base);
  font-family: var(--naw-mono);
  width: 84px;
}

.range-picker :deep(.el-range-separator) {
  color: var(--naw-ink-3);
  padding: 0 4px;
}

.range-picker :deep(.el-input__wrapper) {
  padding: 0 8px;
  height: 28px;
}

.span-note {
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-4);
  font-family: var(--naw-mono);
  white-space: nowrap;
}

/* ==================== 总览 ==================== */
.summary {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  border-bottom: 1px solid var(--naw-rule);
}

.sum-item {
  padding-right: 40px;
  margin-right: 40px;
  border-right: 1px solid var(--naw-rule);
}

.sum-item--last {
  border-right: none;
  margin-left: auto;
  margin-right: 0;
  padding-right: 0;
  text-align: right;
}

.sum-item em {
  display: block;
  font-style: normal;
  font-size: var(--naw-fs-xs);
  letter-spacing: 0.1em;
  color: var(--naw-ink-3);
  margin-bottom: 2px;
}

.sum-item b {
  font-size: var(--naw-fs-3xl);
  font-weight: 400;
  line-height: 1.1;
}

.sum-item .delta {
  margin-left: 8px;
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}

/* ==================== 左栏 ==================== */
.left {
  padding: 16px 26px 0 0;
  border-right: 1px solid var(--naw-rule);
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.block {
  min-height: 0;
}

.left .block:first-child {
  flex: 1;
  display: flex;
  flex-direction: column;
}

/* ==================== 趋势头部：标题 + 粒度切换 ==================== */
.trend-head {
  display: flex;
  align-items: baseline;
  gap: 16px;
}

/* ★ 粒度按钮用和「区间预设」完全相同的下划线语法。
   之前这几个按钮压根没写样式，显示成浏览器默认的灰底方块，和全站格格不入。 */
.gran-switch {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-left: auto;
}

.gs {
  background: none;
  border: none;
  padding: 0 0 2px;
  font-family: inherit;
  font-size: var(--naw-fs-base);
  color: var(--naw-ink-3);
  border-bottom: 1px solid transparent;
  cursor: pointer;
  transition:
    color 0.14s,
    border-color 0.14s;
  white-space: nowrap;
}

.gs:hover {
  color: var(--naw-ink-2);
}

.gs--on {
  color: var(--naw-ink);
  border-bottom-color: var(--naw-seal);
}

.block--cats {
  flex-shrink: 0;
  padding: 14px 0 16px;
  border-top: 1px solid var(--naw-rule);
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

.chart-foot {
  padding-top: 8px;
  margin-top: 8px;
  border-top: 1px solid var(--naw-rule);
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}

.chart-foot b {
  font-size: var(--naw-fs-md);
  color: var(--naw-ink);
  margin-left: 6px;
}

/* ==================== 环形图 + 分类列表 ==================== */
.cats-wrap {
  display: grid;
  grid-template-columns: 132px 1fr;
  gap: 18px;
  align-items: center;
  margin-top: 10px;
}

.donut {
  position: relative;
  width: 132px;
  height: 132px;
  flex-shrink: 0;
}

.donut-svg {
  display: block;
  width: 100%;
  height: 100%;
}

.donut-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}

.donut-center b {
  font-size: var(--naw-fs-lg);
  font-weight: 400;
  color: var(--naw-ink);
}

.donut-center em {
  font-style: normal;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-4);
  margin-top: 1px;
}

/* 图例色块：和环上的颜色一一对应 */
.swatch {
  width: 8px;
  height: 8px;
  border-radius: 1px;
  flex-shrink: 0;
}

/* 分类行 */
.cats {
  margin-top: 10px;
}

.cat-row {
  display: grid;
  grid-template-columns: 20px 8px 1fr 88px 48px;
  align-items: baseline;
  gap: 9px;
  padding: 8px 0;
  border-bottom: 1px solid var(--naw-rule);
}

.cat-row:last-child {
  border-bottom: none;
}

.cat-row .no {
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-4);
}

.cat-row .nm {
  min-width: 0;
}

.cat-row .nm b {
  display: block;
  font-size: var(--naw-fs-base);
  font-weight: 400;
  color: var(--naw-ink);
}

.cat-row .bar {
  display: block;
  height: 3px;
  background: var(--naw-rule);
  margin-top: 4px;
}

.cat-row .bar i {
  display: block;
  height: 100%;
  background: var(--naw-ink-2);
}

.cat-row .kid {
  display: block;
  margin-top: 3px;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cat-row .amt {
  text-align: right;
  font-size: var(--naw-fs-md);
}

.cat-row .pct {
  text-align: right;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

/* ★ 超预算的分类：金额与条都变朱红 */
.cat-row--over .amt,
.cat-row--over .pct {
  color: var(--naw-seal);
}

.cat-row--over .bar i {
  background: var(--naw-seal);
}

/* ==================== 右栏 ==================== */
.right {
  padding: 16px 0 0 24px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.right .block {
  flex-shrink: 0;
  padding-bottom: 16px;
}

.right .block + .block {
  padding-top: 14px;
  border-top: 1px solid var(--naw-rule);
}

.bud-row {
  display: grid;
  grid-template-columns: 1fr auto 44px;
  align-items: baseline;
  gap: 8px;
  padding: 7px 0;
  font-size: var(--naw-fs-sm);
}

.bud-row .bn {
  color: var(--naw-ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bud-row .bv {
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

.bud-row .bpb {
  grid-column: 1 / -1;
  display: block;
  height: 3px;
  background: var(--naw-rule);
}

.bud-row .bpb i {
  display: block;
  height: 100%;
  background: var(--naw-ink-2);
}

.bud-row .bpb i.over {
  background: var(--naw-seal);
}

.bud-row .bp {
  text-align: right;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
  font-family: var(--naw-mono);
}

.bud-row .bp--over {
  color: var(--naw-seal);
}

.block--comment {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.comment {
  margin: 10px 0 0;
  font-size: var(--naw-fs-base);
  line-height: 1.9;
  color: var(--naw-ink-2);
}

.comment--off {
  color: var(--naw-ink-3);
}

/* 未生成时的引导：一句人话 + 一个按钮，不塞满整个区块 */
.comment-idle {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding-top: 10px;
}

.idle-tip {
  margin: 0;
  font-size: var(--naw-fs-base);
  line-height: 1.85;
  color: var(--naw-ink-2);
}

.idle-note {
  margin: 0;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-4);
}

.idle-btn {
  margin-top: 4px;
  font-size: var(--naw-fs-base);
}

.regen {
  margin-top: 10px;
  font-size: var(--naw-fs-sm);
}

.dloading {
  margin-left: 6px;
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}

.right-foot {
  margin-top: auto;
  padding: 10px 0 14px;
  border-top: 1px solid var(--naw-rule);
}

.src-note {
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-4);
}

.link {
  margin-left: 6px;
  color: var(--naw-seal);
  border-bottom: 1px solid var(--naw-seal);
}

/* 加载遮罩 */
.mask {
  position: fixed;
  inset: 0;
  z-index: 90;
  background: rgba(251, 249, 244, 0.72);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: var(--naw-fs-base);
  color: var(--naw-ink-3);
}
</style>
