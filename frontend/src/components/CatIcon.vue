<script setup lang="ts">
/**
 * 分类图标（线性 SVG，`currentColor` 描边）。
 *
 * ★ 为什么不用 emoji：emoji 自带彩色、各平台长得不一样，
 *   十几个分类铺开会变成花花绿绿，和「纸墨印」这套语言冲突。
 *   线段图标能统一线宽、统一取色。
 *
 * ★ key 直接用后端 `t_category.icon` 的值（food / snack / drink ...），
 *   前端只做「名字 → 图形」映射，不另建一套 id。
 *   遇到没见过的 key 一律回落到 `other`，保证永远有图可显示。
 */
import { computed } from 'vue'

const PATHS: Record<string, string> = {
  // 餐饮：碗 + 筷子
  food: 'M3 11h12a5 5 0 0 1-5 5H8a5 5 0 0 1-5-5Z M18 4v8 M18 4a2.5 2.5 0 0 0-2.5 2.5V9',
  // 零食：包装袋
  snack: 'M5 7h10l1 11H4L5 7Z M5 7l1-3h8l1 3 M8 11v3 M12 11v3',
  // 饮品：杯子 + 吸管
  drink: 'M6 6h8l-1 11H7L6 6Z M7 10h6 M13 3l-2 3',
  // 交通：公交正面
  transport:
    'M4 4h12a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1Z M3 9h14 M6 17l-1 1 M14 17l1 1 M6 12.5h.01 M14 12.5h.01',
  // 购物：购物袋
  shopping: 'M4 7h12l-1 10H5L4 7Z M7 7V5a3 3 0 0 1 6 0v2',
  // 居住：房子
  home: 'M3 9l7-5 7 5v8a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9Z M8 18v-5h4v5',
  // 娱乐：音符
  entertainment: 'M7 15V5l8-2v10 M7 15a2 2 0 1 0 0-1 M15 13a2 2 0 1 0 0-1',
  // 医疗：十字
  medical: 'M3 6h14v9H3z M7 6V4h6v2 M10 8.5v4 M8 10.5h4',
  // 学习：书
  education:
    'M3 4h5a2 2 0 0 1 2 2v10a2 2 0 0 0-2-2H3V4Z M17 4h-5a2 2 0 0 0-2 2v10a2 2 0 0 1 2-2h5V4Z',
  // 人情：礼物
  gift: 'M3 8h14v3H3V8Z M4 11h12v7H4v-7Z M10 8v10 M10 8C8.5 8 6.5 7.5 6.5 6S9 4.5 10 8Z M10 8c1.5 0 3.5-.5 3.5-2S11 4.5 10 8Z',
  // 金融：钱币
  finance: 'M10 17a7 7 0 1 0 0-14 7 7 0 0 0 0 14Z M10 6v8 M8 8.5h3a1.5 1.5 0 0 1 0 3H8h3.5',
  // 其他：三个点
  other:
    'M10 11.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3Z M4.5 11.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3Z M15.5 11.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3Z',
  // 收入：公文包
  salary: 'M3 8h14v8H3V8Z M7 8V5.5h6V8 M3 12h14',
  // 副业：闪电
  sidejob: 'M11 3L4 11.5h5l-1 6 7-9h-5l1-5.5Z',
  // 投资：上升折线
  invest: 'M3 14l4-4 3 3 6-6 M12 7h4v4',
}

interface Props {
  icon?: string | null
  size?: number
}

const props = withDefaults(defineProps<Props>(), { icon: 'other', size: 14 })

const key = computed(() => {
  const k = (props.icon ?? '').trim().toLowerCase()
  return k in PATHS ? k : 'other'
})

const d = computed(() => PATHS[key.value])
</script>

<template>
  <svg
    class="cat-icon"
    :width="size"
    :height="size"
    viewBox="0 0 20 20"
    aria-hidden="true"
    focusable="false"
  >
    <path :d="d" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
  </svg>
</template>

<style scoped>
.cat-icon {
  display: block;
  flex-shrink: 0;
}
</style>
