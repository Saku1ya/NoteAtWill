<script setup lang="ts">
/**
 * 分类标签。
 *
 * ★ 与设计稿不同：这里**不用图标**，也不用彩色底。
 *   设计稿验证下来，「账页」这套语言里分类只该是文字 + 一条线，
 *   彩色标签一多就变成后台系统。
 *
 * ★ 这个组件只解决一件事：**缺分类要能被一眼发现**。
 *   所以它只区分三种状态：正常、缺分类（朱红虚线）、待核对（墨灰）。
 */
interface Props {
  name?: string | null
  /** 缺分类时传 true → 朱红虚线 */
  missing?: boolean
  /** 低置信度 → 后面挂一个"待核" */
  low?: boolean
}

withDefaults(defineProps<Props>(), {
  name: '',
  missing: false,
  low: false,
})
</script>

<template>
  <span :class="['cat', { 'cat--miss': missing }]">
    {{ missing ? '未选分类' : name || '未分类' }}
    <em v-if="low && !missing">待核</em>
  </span>
</template>

<style scoped>
.cat {
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
  white-space: nowrap;
}

.cat--miss {
  color: var(--naw-seal);
  border-bottom: 1px dashed var(--naw-seal);
}

.cat em {
  font-style: normal;
  margin-left: 5px;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}
</style>
