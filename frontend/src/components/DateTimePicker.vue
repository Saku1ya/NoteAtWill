<script setup lang="ts">
/**
 * 日期时间选择器：**日历选日期 + 下拉选时间**。
 *
 * ★ 为什么不用原生 `<input type="datetime-local">`：
 *   1. 各浏览器长相完全不同，和「纸墨印」这套语言对不上
 *   2. 它允许选未来时间，而 `billTime` **不能晚于现在**（后端会报 BILL_TIME_FUTURE）
 *   3. 原生控件没法把"今天/昨天"这类高频选项放在手边
 *
 * ★ 值格式：后端要 `yyyy-MM-dd HH:mm:ss`，这里对外就吐这个字符串，
 *   调用方不用再拼一遍（拼错时区/补零很容易出错）。
 */
import { computed, ref } from 'vue'

interface Props {
  /** `yyyy-MM-dd HH:mm:ss` 或 `yyyy-MM-dd HH:mm` */
  modelValue: string
  /** 是否只管日期（true 时输出 `yyyy-MM-dd`） */
  dateOnly?: boolean
}

const props = withDefaults(defineProps<Props>(), { dateOnly: false })

const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

const pickerOpen = ref(false)

/** 拆出日期与时间 */
const parts = computed(() => {
  const v = props.modelValue ?? ''
  const date = v.slice(0, 10)
  // hm 形如 "15:28"，所以时是 [0,2]、分是 [3,5]
  const hm = v.length >= 16 ? v.slice(11, 16) : '12:00'
  return { date, hour: hm.slice(0, 2), minute: hm.slice(3, 5) }
})

/** ★ 不能选未来：上限就是今天 */
function todayStr() {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

function pad(n: number) {
  return String(n).padStart(2, '0')
}

/** 组装并回写 */
function emitValue(date: string, hour: string, minute: string) {
  if (props.dateOnly) {
    emit('update:modelValue', date)
    return
  }
  emit('update:modelValue', `${date} ${hour}:${minute}:00`)
}


function onHourChange(e: Event) {
  const hour = (e.target as HTMLSelectElement).value
  emitValue(parts.value.date, hour, parts.value.minute)
}

function onMinuteChange(e: Event) {
  const minute = (e.target as HTMLSelectElement).value
  emitValue(parts.value.date, parts.value.hour, minute)
}

/** 快捷：现在 / 昨天这个时候 */
function setNow() {
  const d = new Date()
  emitValue(todayStr(), pad(d.getHours()), pad(d.getMinutes()))
}

function setYesterday() {
  const d = new Date(Date.now() - 86400000)
  const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  const now = new Date()
  emitValue(date, pad(now.getHours()), pad(now.getMinutes()))
}

/** el-calendar 需要一个 Date 来定位到当月 */
const calendarValue = computed(() => {
  const d = parts.value.date
  return d ? new Date(`${d}T00:00:00`) : new Date()
})

/** 点日历的某一天 */
function onDatePicked(day: string | Date | null) {
  if (!day) return
  const date =
    typeof day === 'string'
      ? day.slice(0, 10)
      : `${day.getFullYear()}-${pad(day.getMonth() + 1)}-${pad(day.getDate())}`
  let { hour, minute } = parts.value
  // 选了今天且时间已过 → 自动收到现在
  if (date === todayStr()) {
    const now = new Date()
    if (`${hour}:${minute}` > `${pad(now.getHours())}:${pad(now.getMinutes())}`) {
      hour = pad(now.getHours())
      minute = pad(now.getMinutes())
    }
  }
  emitValue(date, hour, minute)
  pickerOpen.value = false
}

const hours = Array.from({ length: 24 }, (_, i) => pad(i))
const minutes = Array.from({ length: 60 }, (_, i) => pad(i))

/** 显示用：日期友好化 */
const dateLabel = computed(() => {
  const d = parts.value.date
  if (!d) return '选择日期'
  if (d === todayStr()) return `今天 · ${d}`
  const y = new Date(Date.now() - 86400000)
  const yStr = `${y.getFullYear()}-${pad(y.getMonth() + 1)}-${pad(y.getDate())}`
  if (d === yStr) return `昨天 · ${d}`
  const [, m, day] = d.split('-')
  return `${Number(m)} 月 ${Number(day)} 日 · ${d}`
})
</script>

<template>
  <div class="dt">
    <!-- 日期：点开日历 -->
    <button type="button" class="dt-date" @click="pickerOpen = !pickerOpen">
      <span class="dt-label">{{ dateLabel }}</span>
      <span class="dt-caret">⌄</span>
    </button>

    <!-- 时间：两个下拉 -->
    <template v-if="!dateOnly">
      <select class="dt-sel" :value="parts.hour" @change="onHourChange">
        <option v-for="h in hours" :key="h" :value="h">{{ h }}</option>
      </select>
      <span class="dt-sep">:</span>
      <select class="dt-sel" :value="parts.minute" @change="onMinuteChange">
        <option v-for="m in minutes" :key="m" :value="m">{{ m }}</option>
      </select>
    </template>

    <!-- 快捷 -->
    <div class="dt-quick">
      <button type="button" class="naw-textbtn" @click="setNow">现在</button>
      <button type="button" class="naw-textbtn" @click="setYesterday">昨天</button>
    </div>

    <!-- 日历浮层：用 el-calendar（纯日历，没有输入框）
         ★ el-date-picker 自带输入框，嵌在自己的浮层里会变成一个"输入框套输入框"。
           el-calendar 只画日历本体，正好放进浮层。 -->
    <div v-if="pickerOpen" class="dt-panel">
      <el-calendar :model-value="calendarValue">
        <template #date-cell="{ data }">
          <button
            type="button"
            :class="[
              'dt-day',
              {
                'dt-day--on': data.day === parts.date,
                'dt-day--today': data.day === todayStr(),
                'dt-day--future': data.day > todayStr(),
              },
            ]"
            :disabled="data.day > todayStr()"
            @click="onDatePicked(data.day)"
          >
            {{ Number(data.day.slice(8, 10)) }}
          </button>
        </template>
      </el-calendar>
    </div>
  </div>
</template>

<style scoped>
.dt {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
}

/* 日期按钮：和 .naw-input 同一套外观 */
.dt-date {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
  padding: 5px 9px;
  background: #fff;
  border: 1px solid var(--naw-rule);
  border-radius: 2px;
  cursor: pointer;
  font-family: inherit;
  font-size: var(--naw-fs-base);
  color: var(--naw-ink);
  text-align: left;
  transition: border-color 0.14s;
}

.dt-date:hover {
  border-color: var(--naw-rule-2);
}

.dt-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dt-caret {
  font-size: 9px;
  color: var(--naw-ink-3);
}

/* 时/分下拉 */
.dt-sel {
  border: 1px solid var(--naw-rule);
  border-radius: 2px;
  background: #fff;
  padding: 4px 2px;
  font-family: var(--naw-mono);
  font-size: var(--naw-fs-base);
  color: var(--naw-ink);
  outline: none;
  cursor: pointer;
  text-align: center;
}

.dt-sel:focus {
  border-color: var(--naw-ink-2);
}

.dt-sep {
  color: var(--naw-ink-3);
  font-family: var(--naw-mono);
}

.dt-quick {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.dt-quick .naw-textbtn {
  font-size: var(--naw-fs-xs);
  white-space: nowrap;
}

/* 日历浮层 */
.dt-panel {
  position: absolute;
  z-index: 400;
  top: calc(100% + 4px);
  left: 0;
  width: 268px;
  background: var(--naw-paper);
  border: 1px solid var(--naw-rule-2);
  box-shadow: 0 6px 20px rgba(28, 25, 23, 0.12);
  padding: 6px;
}

/* ★ el-calendar 默认带顶部大标题和一堆留白，压掉只留日期格子 */
.dt-panel :deep(.el-calendar) {
  background: transparent;
}

.dt-panel :deep(.el-calendar__header) {
  display: none;
}

.dt-panel :deep(.el-calendar__body) {
  padding: 0;
}

.dt-panel :deep(.el-calendar-table .el-calendar-day) {
  height: 30px;
  padding: 0;
  display: grid;
  place-items: center;
}

.dt-panel :deep(.el-calendar-table thead th) {
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
  font-weight: 400;
  padding: 2px 0;
}

/* ★ el-calendar 的星期表头跟着 Element 的语言包走，默认英文（Sun/Mon…）。
   实测 DOM 是 <th scope="col">Sun</th> —— **文字直接在 th 里，没有 .cell 包裹**，
   所以用 th 自身的 font-size:0 把英文藏掉，再用 ::after 塞中文。 */
.dt-panel :deep(.el-calendar-table thead th) {
  font-size: 0;
}

.dt-panel :deep(.el-calendar-table thead th::after) {
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

.dt-panel :deep(.el-calendar-table thead th:nth-child(1)::after) { content: '日'; }
.dt-panel :deep(.el-calendar-table thead th:nth-child(2)::after) { content: '一'; }
.dt-panel :deep(.el-calendar-table thead th:nth-child(3)::after) { content: '二'; }
.dt-panel :deep(.el-calendar-table thead th:nth-child(4)::after) { content: '三'; }
.dt-panel :deep(.el-calendar-table thead th:nth-child(5)::after) { content: '四'; }
.dt-panel :deep(.el-calendar-table thead th:nth-child(6)::after) { content: '五'; }
.dt-panel :deep(.el-calendar-table thead th:nth-child(7)::after) { content: '六'; }

.dt-panel :deep(.el-calendar-table td.is-selected) {
  background: transparent;
}

/* 日期格子 */
.dt-day {
  width: 26px;
  height: 26px;
  border: 1px solid transparent;
  border-radius: 2px;
  background: none;
  font-family: var(--naw-serif);
  font-size: var(--naw-fs-base);
  color: var(--naw-ink-2);
  cursor: pointer;
  transition: background 0.12s;
}

.dt-day:hover:not(:disabled) {
  background: var(--naw-paper-2);
  color: var(--naw-ink);
}

/* 选中：墨块白字，和全站"选中"一致 */
.dt-day--on {
  background: var(--naw-ink) !important;
  color: var(--naw-paper) !important;
}

/* 今天：朱红框，但没选中时不填空 */
.dt-day--today:not(.dt-day--on) {
  border-color: var(--naw-seal);
  color: var(--naw-seal);
}

/* 未来日期：不可选 */
.dt-day--future,
.dt-day:disabled {
  color: var(--naw-ink-4);
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
