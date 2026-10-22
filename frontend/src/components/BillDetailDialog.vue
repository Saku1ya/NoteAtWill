<script setup lang="ts">
/**
 * 账单详情（只读）。
 *
 * ★ 为什么抽成独立组件：
 *   对话流里点卡片要看详情，账单页右侧详情栏也展示同一批字段。
 *   字段列表（备注/金额/类型/分类/账户/时间/来源）只该定义一次，
 *   否则以后加字段要改两处，必然漏。
 *
 * ★ 与账单页的区别：这里是**只读**的。
 *   改单仍然只在账单页做 —— 弹窗里再塞一套编辑逻辑，
 *   等于把同一份表单写两遍，且容易出现"两处校验不一致"。
 */
import type { BillVO } from '../api/types'

defineProps<{ bill: BillVO }>()

const emit = defineEmits<{ (e: 'close'): void; (e: 'go-edit', id: number): void }>()

function money(v: string | number | null | undefined) {
  return Number(v ?? 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

const sourceLabel = (s: string) =>
  ({ AI: 'AI 解析', MANUAL: '手动记账', IMPORT: '导入' })[s] ?? s
</script>

<template>
  <div class="mask" @click.self="emit('close')">
    <div class="dialog">
      <div class="dialog-head">
        <span>这笔账</span>
        <button class="x" @click="emit('close')">×</button>
      </div>

      <!-- 金额是主角 -->
      <div class="amt-block">
        <b :class="['naw-num', { 'naw-num--income': bill.type === 'INCOME' }]">
          {{ bill.type === 'INCOME' ? '+' : '−' }}{{ money(bill.amount) }}
        </b>
        <div class="amt-meta">
          <span>{{ bill.type === 'INCOME' ? '收入' : '支出' }}</span>
          <span class="dot">·</span>
          <span class="naw-mono">{{ bill.billTime }}</span>
        </div>
      </div>

      <dl class="fields">
        <div class="f">
          <dt>备注</dt>
          <dd>{{ bill.remark || '—' }}</dd>
        </div>
        <div class="f">
          <dt>分类</dt>
          <dd>{{ bill.categoryName || '未分类' }}</dd>
        </div>
        <div class="f">
          <dt>账户</dt>
          <dd>{{ bill.accountName || '未关联账户' }}</dd>
        </div>
        <div class="f">
          <dt>账本</dt>
          <dd>{{ bill.bookName }}</dd>
        </div>
        <div class="f">
          <dt>来源</dt>
          <dd>
            {{ sourceLabel(bill.source) }}
            <span class="id naw-mono">#{{ bill.id }}</span>
          </dd>
        </div>
        <div class="f">
          <dt>记账时间</dt>
          <dd class="naw-mono">{{ bill.createTime }}</dd>
        </div>
      </dl>

      <div class="dialog-foot">
        <span class="tip">想改金额或分类，去账单页改</span>
        <button class="naw-textbtn" @click="emit('close')">关闭</button>
        <button class="naw-btn" @click="emit('go-edit', bill.id)">去账单页修改</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 500;
  background: rgba(28, 25, 23, 0.28);
  display: grid;
  place-items: center;
}

.dialog {
  width: 380px;
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

/* 金额块：整张弹窗的视觉重心 */
.amt-block {
  padding: 16px 0 14px;
  border-bottom: 1px solid var(--naw-rule);
}

.amt-block b {
  font-size: var(--naw-fs-3xl);
  font-weight: 400;
  line-height: 1.1;
}

.amt-meta {
  margin-top: 5px;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
  display: flex;
  gap: 6px;
  align-items: baseline;
}

.dot {
  color: var(--naw-ink-4);
}

/* 字段表：label 与 value 两列，像账本的填写格 */
.fields {
  margin: 0;
  padding: 0;
}

.f {
  display: grid;
  grid-template-columns: 62px 1fr;
  align-items: baseline;
  gap: 10px;
  padding: 9px 0;
  border-bottom: 1px solid var(--naw-rule);
}

.f:last-child {
  border-bottom: none;
}

.f dt {
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}

.f dd {
  margin: 0;
  font-size: var(--naw-fs-base);
  color: var(--naw-ink);
  min-width: 0;
  word-break: break-word;
}

.id {
  margin-left: 8px;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-4);
}

.dialog-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--naw-rule);
}

.dialog-foot .tip {
  margin-right: auto;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-4);
  line-height: 1.5;
  max-width: 150px;
}
</style>
