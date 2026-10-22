<script setup lang="ts">
/**
 * 分类选择器（图标 + 名字，网格铺开）。
 *
 * ★★ 为什么不用原生 `<select>`（用户截图反馈的直接原因）：
 *   原生 select 的选项列表会**向下无限延伸**，分类一多就盖住整个弹窗，
 *   而且没法定制样式、塞不进图标。这里改成自己实现的下拉：
 *   **固定高度 + 内部网格滚动**，无论多少分类都不会超出边界。
 *
 * ★ 交互约定：
 *   - 一级分类**可直接选**（后端允许 category_id 指向一级）
 *   - 有二级的显示为分组标题 + 子项网格；没有二级的直接是一级按钮
 *   - 下拉贴着触发框向下展开，内容区最多 248px，超出内部滚动
 *   - 点外部 / Esc 关闭
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import CatIcon from './CatIcon.vue'
import type { CategoryTreeVO } from '../api/types'

interface Props {
  modelValue: number | null
  tree: CategoryTreeVO[]
  placeholder?: string
}

const props = withDefaults(defineProps<Props>(), { placeholder: '选分类' })

const emit = defineEmits<{
  (e: 'update:modelValue', v: number | null): void
  (e: 'change', v: number | null): void
}>()

const open = ref(false)
const root = ref<HTMLElement>()
const search = ref('')

/**
 * 下拉要往下开还是往上开。
 *
 * ★ 触发框在弹层底部时，向下开会**伸出弹层甚至屏幕外**（用户截图里就是这样）。
 *   这里在打开时量一次剩余空间：下方放不下（< 240px）且上方更宽裕，就改成向上开。
 */
const dropUp = ref(false)
/** 内容区最大高度，按可用空间动态收，避免伸到屏幕外 */
const bodyMaxH = ref(248)

function decideDirection() {
  const el = root.value
  if (!el) return
  const r = el.getBoundingClientRect()
  const viewportH = window.innerHeight
  const spaceBelow = viewportH - r.bottom - 12
  const spaceAbove = r.top - 12
  // 面板额外有搜索框(约 46px) + 边框，所以可用空间要再扣掉
  const need = 240
  if (spaceBelow < need && spaceAbove > spaceBelow) {
    dropUp.value = true
    bodyMaxH.value = Math.max(120, Math.min(248, spaceAbove - 56))
  } else {
    dropUp.value = false
    bodyMaxH.value = Math.max(120, Math.min(248, spaceBelow - 56))
  }
}

/** 当前选中（一级或二级） */
const selected = computed(() => {
  for (const p of props.tree) {
    if (p.id === props.modelValue) return { name: p.name, icon: p.icon }
    for (const c of p.children ?? []) {
      if (c.id === props.modelValue) return { name: `${p.name}/${c.name}`, icon: c.icon ?? p.icon }
    }
  }
  return null
})

/** 搜索过滤：一级命中保留整组，二级命中只留命中的子项 */
const filtered = computed(() => {
  const q = search.value.trim()
  if (!q) return props.tree
  const out: CategoryTreeVO[] = []
  for (const p of props.tree) {
    const kids = (p.children ?? []).filter((c) => c.name.includes(q))
    const selfHit = p.name.includes(q)
    if (!selfHit && !kids.length) continue
    out.push({ ...p, children: selfHit ? p.children ?? [] : kids })
  }
  return out
})

function pick(id: number | null) {
  emit('update:modelValue', id)
  emit('change', id)
  open.value = false
  search.value = ''
}

function toggle() {
  open.value = !open.value
  if (open.value) {
    search.value = ''
    decideDirection()
  }
}

function onDocClick(e: MouseEvent) {
  if (!open.value) return
  if (root.value && !root.value.contains(e.target as Node)) {
    open.value = false
    search.value = ''
  }
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && open.value) {
    open.value = false
    search.value = ''
  }
}

onMounted(() => {
  document.addEventListener('mousedown', onDocClick)
  document.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocClick)
  document.removeEventListener('keydown', onKey)
})
</script>

<template>
  <div ref="root" class="csel">
    <button type="button" :class="['csel-trigger', { 'csel-trigger--open': open }]" @click="toggle">
      <CatIcon v-if="selected" :icon="selected.icon" :size="14" class="csel-icon" />
      <span v-if="selected" class="csel-text">{{ selected.name }}</span>
      <span v-else class="csel-ph">{{ placeholder }}</span>
      <span class="csel-caret">{{ open ? '⌃' : '⌄' }}</span>
    </button>

    <div v-if="open" :class="['csel-panel', { 'csel-panel--up': dropUp }]">
      <div class="csel-search">
        <input v-model="search" class="csel-search-input" placeholder="搜分类…" />
      </div>

      <div class="csel-body" :style="{ maxHeight: bodyMaxH + 'px' }">
        <div v-for="p in filtered" :key="p.id" class="csel-group">
          <template v-if="p.children?.length">
            <div class="csel-group-head">
              <CatIcon :icon="p.icon" :size="13" />
              <span class="csel-group-name">{{ p.name }}</span>
              <button type="button" class="csel-pick-top" @click="pick(p.id)">用一级</button>
            </div>
            <div class="csel-grid">
              <button
                v-for="c in p.children"
                :key="c.id"
                type="button"
                :class="['csel-cell', { 'csel-cell--on': c.id === modelValue }]"
                @click="pick(c.id)"
              >
                <CatIcon :icon="c.icon ?? p.icon" :size="13" />
                <span class="csel-cell-name">{{ c.name }}</span>
              </button>
            </div>
          </template>

          <button
            v-else
            type="button"
            :class="['csel-cell', 'csel-cell--flat', { 'csel-cell--on': p.id === modelValue }]"
            @click="pick(p.id)"
          >
            <CatIcon :icon="p.icon" :size="13" />
            <span class="csel-cell-name">{{ p.name }}</span>
          </button>
        </div>

        <div v-if="!filtered.length" class="csel-empty">没找到「{{ search }}」</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.csel {
  position: relative;
  min-width: 0;
}

.csel-trigger {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  padding: 5px 9px;
  background: #fff;
  border: 1px solid var(--naw-rule-3);
  border-radius: 2px;
  cursor: pointer;
  font-family: inherit;
  font-size: var(--naw-fs-base);
  color: var(--naw-ink);
  text-align: left;
  transition: border-color 0.14s;
}

.csel-trigger:hover {
  border-color: var(--naw-ink-2);
}

.csel-trigger--open {
  border-color: var(--naw-ink);
}

.csel-icon {
  color: var(--naw-ink-2);
}

.csel-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.csel-ph {
  flex: 1;
  color: var(--naw-ink-4);
}

.csel-caret {
  margin-left: auto;
  font-size: 9px;
  color: var(--naw-ink-3);
}

/* ---------- 下拉面板：固定高度，绝不无限延伸 ---------- */
.csel-panel {
  position: absolute;
  z-index: 300;
  top: calc(100% + 4px);
  left: 0;
  width: max(280px, 100%);
  max-width: 92vw;
  background: var(--naw-paper);
  border: 1px solid var(--naw-rule-2);
  box-shadow: 0 6px 20px rgba(28, 25, 23, 0.12);
  display: flex;
  flex-direction: column;
  max-height: 80vh;
}

/* ★ 向上展开：触发框贴着弹层底部时用这个，避免下拉伸出弹层 */
.csel-panel--up {
  top: auto;
  bottom: calc(100% + 4px);
}

.csel-search {
  flex-shrink: 0;
  padding: 7px;
  border-bottom: 1px solid var(--naw-rule);
}

.csel-search-input {
  width: 100%;
  border: 1px solid var(--naw-rule);
  border-radius: 2px;
  background: #fff;
  padding: 5px 8px;
  font-size: var(--naw-fs-sm);
  outline: none;
  box-sizing: border-box;
}

.csel-search-input:focus {
  border-color: var(--naw-ink-2);
}

/* ★ 关键：内容区最高 248px，内部滚动，面板高度就锁住了 */
.csel-body {
  max-height: 248px;
  overflow-y: auto;
  scrollbar-gutter: stable;
  padding: 6px 8px 8px;
}

.csel-group + .csel-group {
  margin-top: 4px;
  padding-top: 6px;
  border-top: 1px solid var(--naw-rule);
}

.csel-group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 2px 5px;
  color: var(--naw-ink-3);
}

.csel-group-name {
  font-size: var(--naw-fs-xs);
  letter-spacing: 0.1em;
}

.csel-pick-top {
  margin-left: auto;
  background: none;
  border: none;
  padding: 0 0 1px;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
  border-bottom: 1px solid var(--naw-rule-2);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.12s;
}

.csel-group-head:hover .csel-pick-top {
  opacity: 1;
}

.csel-pick-top:hover {
  color: var(--naw-ink);
}

/* ---------- 网格：每行 2 格，图标 + 名字 ---------- */
.csel-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 3px;
}

.csel-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  background: none;
  border: 1px solid transparent;
  border-radius: 2px;
  cursor: pointer;
  font-family: inherit;
  font-size: var(--naw-fs-base);
  color: var(--naw-ink-2);
  text-align: left;
  min-width: 0;
  transition:
    background 0.12s,
    border-color 0.12s;
}

.csel-cell:hover {
  background: var(--naw-paper-2);
  color: var(--naw-ink);
}

/* 选中：朱红左线 + 浅纸底，与全站的"选中"表达一致 */
.csel-cell--on {
  background: var(--naw-paper-2);
  color: var(--naw-ink);
  border-color: var(--naw-rule-2);
  box-shadow: inset 2px 0 0 var(--naw-seal);
}

.csel-cell--flat {
  width: 100%;
  margin-top: 3px;
}

.csel-cell-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.csel-empty {
  padding: 16px 8px;
  text-align: center;
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}
</style>
