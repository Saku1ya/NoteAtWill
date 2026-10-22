<script setup lang="ts">
/**
 * 对话流组件。
 *
 * ★★ 数据源是 `/api/chat/messages`（游标分页，**最新在前**），
 *   而不是 `t_bill`。区别很重要：
 *   - 账单表只记"花了一笔"
 *   - 对话表记"谁在什么时候说了什么"：AI 的问候、用户的原话、记账成功后的卡片
 *   所以这里能看到 AI 说过的话，账单列表看不到。
 *
 * ★ `meta_json` 目前恒为 NULL（后端没填），卡片信息只能从 `content` 取，
 *   金额和分类要按 `billId` 回查账单补上。
 *
 * ★ 展示顺序：**最新在最上**（倒序）。
 *   记账场景「刚刚发生的事最相关」，而且确认入账后卡片就在眼前，不用滚到底。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { pageMessages, deleteMessage } from '../api/chat'
import { getBill } from '../api/bill'
import { BusinessError } from '../api/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useBookStore } from '../stores'
import BillDetailDialog from './BillDetailDialog.vue'
import type { BillVO, ChatMessageVO } from '../api/types'

const bookStore = useBookStore()
const router = useRouter()

const items = ref<ChatMessageVO[]>([])
/** billId → 账单详情（补金额与分类） */
const billCache = ref<Record<number, BillVO>>({})
const loading = ref(false)
const nextCursor = ref<number | null>(null)
const hasMore = ref(false)
const loadingMore = ref(false)

/** 加载首屏 + 补齐账单详情 */
async function load() {
  loading.value = true
  try {
    const page = await pageMessages(undefined, 30, bookStore.currentId)
    items.value = page.items
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
    await hydrate(page.items)
  } catch {
    items.value = []
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (!hasMore.value || loadingMore.value || nextCursor.value == null) return
  loadingMore.value = true
  try {
    const page = await pageMessages(nextCursor.value, 30, bookStore.currentId)
    items.value = [...items.value, ...page.items]
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
    await hydrate(page.items)
  } finally {
    loadingMore.value = false
  }
}

/** 批量回查账单（去重 + 忽略失败） */
async function hydrate(list: ChatMessageVO[]) {
  const ids = list
    .filter((m) => m.type === 'BILL_CARD' && m.billId && !billCache.value[m.billId])
    .map((m) => m.billId as number)
  const uniq = [...new Set(ids)]
  if (!uniq.length) return
  const results = await Promise.allSettled(uniq.map((id) => getBill(id)))
  results.forEach((r, i) => {
    if (r.status === 'fulfilled') billCache.value[uniq[i]] = r.value
  })
}

/** 对外暴露一个刷新方法，记账成功后由父组件调用 */
defineExpose({ reload: load })

onMounted(load)

// ============================================================================
// 展示
// ============================================================================

/** 按天分组（保持"最新在最上"） */
const groups = computed(() => {
  const map = new Map<string, ChatMessageVO[]>()
  for (const m of items.value) {
    const day = m.createTime.slice(0, 10)
    if (!map.has(day)) map.set(day, [])
    map.get(day)!.push(m)
  }
  return [...map.entries()].map(([day, list]) => ({ day, list }))
})

function dayLabel(day: string) {
  const today = new Date()
  const fmt = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  if (day === fmt(today)) return '今天'
  const y = new Date(today.getTime() - 86400000)
  if (day === fmt(y)) return '昨天'
  const [, m, d] = day.split('-')
  return `${Number(m)} 月 ${Number(d)} 日`
}

function timeOf(t: string) {
  return t.slice(11, 16)
}

function billOf(m: ChatMessageVO): BillVO | undefined {
  return m.billId ? billCache.value[m.billId] : undefined
}

function money(v: string | number | null | undefined) {
  return Number(v ?? 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

/** 卡片标题：剥掉后端加的「已记录：」前缀，自己排版 */
function cardTitle(m: ChatMessageVO) {
  const c = m.content ?? ''
  return c.replace(/^已记录[:：]\s*/, '').replace(/\s*\d+(\.\d+)?\s*元\s*$/, '').trim() || '一笔账'
}

// ---------------------------------------------------------------------------
// 卡片详情
//
// ★ 数据来自 `billCache`（按 billId 回查的那份账单），不是消息本身 ——
//   消息里只有一句"已记录：xxx 元"，账户、来源、记账时间都在账单上。
// ---------------------------------------------------------------------------

const detailBill = ref<BillVO | null>(null)

function openDetail(m: ChatMessageVO) {
  const b = billOf(m)
  if (!b) return
  detailBill.value = b
}

function closeDetail() {
  detailBill.value = null
}

/** 去账单页修改：带上 billId，账单页会读 query 自动选中那一笔 */
function goEdit(id: number) {
  detailBill.value = null
  router.push({ name: 'bills', query: { bill: String(id) } })
}

/** 删除：账单卡片要二次确认，并明确会连带删除账单 */
async function remove(m: ChatMessageVO) {
  const isBill = m.type === 'BILL_CARD'
  try {
    await ElMessageBox.confirm(
      isBill
        ? '这条卡片对应一笔真实账单，删除后这笔账也会从统计和预算里消失。'
        : '删除后这条消息不再显示。',
      isBill ? '删除这张卡片？' : '删除这条消息？',
      { confirmButtonText: '删除', cancelButtonText: '算了', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    // ★ cascadeBill=true 才会连带删账单，默认 false 只删消息
    await deleteMessage(m.id, isBill)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '删除失败')
  }
}
</script>

<template>
  <div class="stream">
    <div v-if="loading" class="stream-empty">
      <span class="naw-dots"><i /><i /><i /></span>
    </div>

    <div v-else-if="!items.length" class="stream-empty">
      还没有任何记录。说一句话，记账会出现在这里。
    </div>

    <div v-else class="stream-body">
      <template v-for="g in groups" :key="g.day">
        <div class="day-divider">
          <span>{{ dayLabel(g.day) }}</span>
        </div>

        <div
          v-for="m in g.list"
          :key="m.id"
          :class="['msg', `msg--${m.type.toLowerCase()}`]"
        >
          <!-- 问候：唯一用对话气泡的地方，因为它是 AI"在说话" -->
          <template v-if="m.type === 'GREETING'">
            <span class="msg-avatar">AI</span>
            <div class="bubble">
              <p class="bubble-text">{{ m.content }}</p>
              <span class="msg-time naw-mono">{{ timeOf(m.createTime) }}</span>
            </div>
          </template>

          <!-- 账单卡片 -->
          <template v-else-if="m.type === 'BILL_CARD'">
            <span class="msg-avatar">记</span>
            <!-- ★ 整张卡可点：打开只读详情（和账单页右侧那套字段一致） -->
            <div
              class="card"
              :class="{ 'card--clickable': !!billOf(m) }"
              :title="billOf(m) ? '查看详情' : ''"
              @click="openDetail(m)"
            >
              <div class="card-main">
                <span class="card-title">{{ cardTitle(m) }}</span>
                <span
                  v-if="billOf(m)"
                  :class="[
                    'card-amount',
                    'naw-num',
                    { 'naw-num--income': billOf(m)!.type === 'INCOME' },
                  ]"
                >
                  {{ billOf(m)!.type === 'INCOME' ? '+' : '−' }}{{ money(billOf(m)!.amount) }}
                </span>
              </div>
              <div class="card-meta">
                <span v-if="billOf(m)?.categoryName" class="chip">
                  {{ billOf(m)!.categoryName }}
                </span>
                <span v-if="billOf(m)?.accountName" class="chip chip--ghost">
                  {{ billOf(m)!.accountName }}
                </span>
                <span class="src">
                  {{ m.triggerType === 'AI_PARSE' ? 'AI 解析' : '手动记账' }}
                </span>
                <span class="msg-time naw-mono">{{ timeOf(m.createTime) }}</span>
                <!-- ★ stop：点删除不该顺带打开详情 -->
                <button class="del" title="删除" @click.stop="remove(m)">×</button>
              </div>
            </div>
          </template>

          <!-- 其它（TEXT / STAT_CARD）：纯文字 -->
          <template v-else>
            <span class="msg-avatar">AI</span>
            <div class="bubble">
              <p class="bubble-text">{{ m.content }}</p>
              <span class="msg-time naw-mono">{{ timeOf(m.createTime) }}</span>
            </div>
          </template>
        </div>
      </template>

      <div v-if="hasMore" class="more">
        <button class="naw-textbtn" :disabled="loadingMore" @click="loadMore">
          {{ loadingMore ? '加载中…' : '看更早的记录' }}
        </button>
      </div>
    </div>

    <!-- 账单详情（只读）；"去账单页修改"会带 ?bill= 跳过去并自动选中 -->
    <BillDetailDialog
      v-if="detailBill"
      :bill="detailBill"
      @close="closeDetail"
      @go-edit="goEdit"
    />
  </div>
</template>

<style scoped>
.stream {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.stream-empty {
  flex: 1;
  display: grid;
  place-content: center;
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
  padding: 0 8px;
  text-align: center;
  line-height: 1.8;
}

.stream-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-gutter: stable;
  padding-right: 2px;
}

/* 日期分隔 */
.day-divider {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 4px 0 10px;
  color: var(--naw-ink-4);
  font-size: var(--naw-fs-xs);
  letter-spacing: 0.06em;
}

.day-divider::before,
.day-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--naw-rule);
}

/* 一条消息：头像 + 内容 */
.msg {
  display: grid;
  grid-template-columns: 22px 1fr;
  gap: 9px;
  margin-bottom: 10px;
}

.msg-avatar {
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border: 1px solid var(--naw-rule-2);
  border-radius: 50%;
  font-size: 9.5px;
  color: var(--naw-ink-3);
  user-select: none;
}

/* ---------- 问候气泡 ---------- */
.bubble {
  background: var(--naw-paper-2);
  border-radius: 2px;
  padding: 8px 11px;
  min-width: 0;
}

.bubble-text {
  margin: 0;
  font-size: var(--naw-fs-base);
  line-height: 1.8;
  color: var(--naw-ink-2);
  white-space: pre-wrap;
  word-break: break-word;
}

/* ---------- 账单卡片 ---------- */
.card {
  background: #fff;
  border: 1px solid var(--naw-rule);
  border-radius: 2px;
  padding: 8px 11px;
  min-width: 0;
  transition: border-color 0.14s;
}

.card:hover {
  border-color: var(--naw-rule-2);
}

/* ★ 可点击的卡片：给一点"能点"的暗示（边框加深 + 指针），
   但不加阴影/位移，保持账页的安静 */
.card--clickable {
  cursor: pointer;
}

.card--clickable:hover {
  border-color: var(--naw-ink-3);
}

.card-main {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.card-title {
  flex: 1;
  min-width: 0;
  font-size: var(--naw-fs-base);
  color: var(--naw-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-amount {
  font-size: var(--naw-fs-lg);
  flex-shrink: 0;
}

.card-meta {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 4px;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
  min-width: 0;
}

.chip {
  padding: 0 5px;
  border-radius: 2px;
  background: var(--naw-paper-2);
  color: var(--naw-ink-2);
  white-space: nowrap;
}

.chip--ghost {
  background: transparent;
  border: 1px solid var(--naw-rule);
}

.src {
  color: var(--naw-ink-4);
  white-space: nowrap;
}

.msg-time {
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-4);
  white-space: nowrap;
}

.bubble .msg-time {
  display: block;
  margin-top: 3px;
}

.del {
  margin-left: auto;
  background: none;
  border: none;
  padding: 0 2px;
  font-size: 13px;
  line-height: 1;
  color: transparent;
  cursor: pointer;
  transition: color 0.12s;
}

.card:hover .del {
  color: var(--naw-ink-4);
}

.del:hover {
  color: var(--naw-seal);
}

.more {
  display: flex;
  justify-content: center;
  padding: 6px 0 10px;
}

.more .naw-textbtn {
  font-size: var(--naw-fs-xs);
}
</style>
