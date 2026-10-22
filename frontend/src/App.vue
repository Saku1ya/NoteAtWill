<script setup lang="ts">
/**
 * 应用根组件。
 *
 * ★ 只负责"路由守卫生效前的骨架"和"会话恢复"：
 *   刷新页面时 token 还在 localStorage，但内存里的 user 是空的，
 *   这里先拉一次 /api/user/me，拿到再放行，避免页面闪一下变成"未登录"。
 *
 * ★ 会话恢复**失败不能拦路**：
 *   后端重启的几十秒里所有请求都会失败。这时如果还把页面卡住，
 *   用户看到的就是"账本没了、数据全空"——而实际上数据都在库里。
 *   所以这里失败也放行，把失败留给页面自己提示，用户刷新一次就好了。
 */
import { onMounted, ref } from 'vue'
import { useUserStore, useBookStore } from './stores'
import { getToken } from './api/request'

const ready = ref(false)
const userStore = useUserStore()
const bookStore = useBookStore()

onMounted(async () => {
  if (getToken()) {
    // ★ 并行拉：user 和 books 互不依赖，串行会让启动多等一个来回
    const [u, ok] = await Promise.all([userStore.fetchUser(), bookStore.fetchBooks()])
    // 用户信息没拿到但账本拿到了 → 仍可用（顶栏会退化成手机号/我）
    // 两个都失败 → 大概率后端没起来，交给页面显示空态，不在这里拦
    if (!u && !ok) {
      // 什么都不做：ready 会置 true，页面自行展示空态
    }
  }
  ready.value = true
})
</script>

<template>
  <router-view v-if="ready" />
  <div v-else class="boot">
    <span class="boot-mark">记</span>
    <span class="naw-dots"><i /><i /><i /></span>
  </div>
</template>

<style scoped>
/* 启动态：只放一枚章和三个点，不做花哨 loading */
.boot {
  height: 100vh;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 18px;
  background: var(--naw-paper);
}

.boot-mark {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border: 2px solid var(--naw-seal);
  border-radius: 3px;
  color: var(--naw-seal);
  font-family: var(--naw-serif);
  font-size: 22px;
  line-height: 1;
  transform: rotate(-7deg);
}
</style>
