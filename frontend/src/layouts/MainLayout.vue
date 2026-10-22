<script setup lang="ts">
/**
 * 主布局：左侧导航 + 顶栏 + 内容区。
 *
 * ★ 与设计稿一致：这里没有图标。
 *   账页的语言用字符和编号，不用 emoji 或图标库 —— 一旦加图标就变成后台系统。
 *
 * ★ 顶栏右侧只放"已同步时间"和用户，不放搜索框和通知铃。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore, useBookStore } from '../stores'
import { logout } from '../api/auth'
import { ElMessage } from 'element-plus'
import { createBook } from '../api/base'
import { BusinessError } from '../api/request'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const bookStore = useBookStore()

const nav = [
  { name: 'desk', label: '记账台' },
  { name: 'bills', label: '账单' },
  { name: 'stats', label: '统计' },
  { name: 'budget', label: '预算' },
  { name: 'accounts', label: '账户' },
]

const activeName = computed(() => route.name as string)
const pageTitle = computed(() => (route.meta.title as string) || '')

const bookOpen = ref(false)
function pickBook(id: number) {
  if (id === bookStore.currentId) {
    bookOpen.value = false
    return
  }
  bookStore.select(id)
  bookOpen.value = false
  // 切账本后各页要重新拉数据：整页重载最干净，不会残留上个账本的状态
  router.go(0)
}

const userOpen = ref(false)

// ============================================================================
// 新建账本（从账本下拉里直接创建，不用跳设置页）
// ============================================================================

const createBookOpen = ref(false)
const newBookName = ref('')
const savingBook = ref(false)

function openCreateBook() {
  bookOpen.value = false
  newBookName.value = ''
  createBookOpen.value = true
}

async function doCreateBook() {
  const name = newBookName.value.trim()
  if (!name) {
    ElMessage.error('账本名不能为空')
    return
  }
  savingBook.value = true
  try {
    const created = await createBook({ name })
    await bookStore.fetchBooks()
    // 建完直接切过去 —— 用户建新账本的目的通常就是马上往里记
    if (created?.id) bookStore.select(created.id)
    createBookOpen.value = false
    ElMessage.success(`「${name}」已建好`)
    syncTime.value = nowHM()
    // 切换账本后各页要重拉数据
    router.go(0)
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '新建失败')
  } finally {
    savingBook.value = false
  }
}


async function doLogout() {
  try {
    await logout()
  } catch {
    /* 登出失败也要清本地，否则用户卡在登录态出不去 */
  }
  userStore.reset()
  bookStore.reset()
  router.replace({ name: 'login' })
}

function onDocClick(e: MouseEvent) {
  const t = e.target as HTMLElement
  if (!t.closest('.book-wrap')) bookOpen.value = false
  if (!t.closest('.user-wrap')) userOpen.value = false
}

/**
 * 重试载入。
 *
 * ★ 用户信息也一起补：启动时 /api/user/me 和 /api/books 很可能同时失败，
 *   只补账本的话顶栏会一直显示兜底的「我」。
 * ★ 成功后整页重载：各页的 onMounted 只在首次挂载时拉数据，
 *   store 补上了它们也不会自己更新。
 */
async function retryLoad() {
  const [, ok] = await Promise.all([userStore.fetchUser(), bookStore.fetchBooks()])
  if (ok) {
    syncTime.value = nowHM()
    router.go(0)
  } else {
    ElMessage.error(bookStore.loadError || '还是没连上，稍后再试')
  }
}

const syncTime = ref('')

function nowHM() {
  const d = new Date()
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

onMounted(() => {
  document.addEventListener('click', onDocClick)
  syncTime.value = nowHM()
})

watch(
  () => route.fullPath,
  () => {
    bookOpen.value = false
    userOpen.value = false
  },
)
</script>

<template>
  <div class="layout">
    <aside class="side">
      <div class="brand">
        <span class="brand-name">随心记</span>
      </div>

      <div class="book-wrap">
        <button class="book-switch" @click="bookOpen = !bookOpen">
          <span class="book-name">{{ bookStore.current?.name ?? '未选择账本' }}</span>
          <span class="book-caret">{{ bookOpen ? '⌃' : '⌄' }}</span>
        </button>
        <div v-if="bookOpen" class="book-menu">
          <button
            v-for="b in bookStore.books"
            :key="b.id"
            :class="['book-item', { 'book-item--on': b.id === bookStore.currentId }]"
            @click="pickBook(b.id)"
          >
            {{ b.name }}
          </button>
          <div v-if="!bookStore.books.length" class="book-empty">还没有账本</div>

          <!-- ★ 新建账本放在这里：它和"切换账本"是同一件事，用户想加账本时
               第一反应就是点账本名，而不是翻到设置页去找。 -->
          <button class="book-item book-item--add" @click="openCreateBook">
            <span class="plus">+</span> 新建账本
          </button>
        </div>
      </div>

      <!-- ★ 下拉展开时导航整体下移：
           下拉是 absolute 的，不占文档流，会盖住"记账台/账单"。
           与其让它浮在文字上（难看且容易误点），不如把下面的内容推开。 -->
      <nav :class="['nav', { 'nav--pushed': bookOpen }]">
        <router-link
          v-for="n in nav"
          :key="n.name"
          :to="{ name: n.name }"
          :class="['nav-item', { 'nav-item--on': activeName === n.name }]"
        >
          {{ n.label }}
        </router-link>
      </nav>

      <div class="side-foot">
        <router-link
          :to="{ name: 'settings' }"
          :class="['nav-item', { 'nav-item--on': activeName === 'settings' }]"
        >
          设置
        </router-link>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <h1 class="page-title">{{ pageTitle }}</h1>
        <div class="top-right">
          <span class="top-kv">已同步 <b class="naw-mono">{{ syncTime }}</b></span>
          <div class="user-wrap">
            <button class="user-chip" @click="userOpen = !userOpen">
              {{ userStore.nickname }}
            </button>
            <div v-if="userOpen" class="user-menu">
              <router-link :to="{ name: 'settings' }" class="user-item">账号设置</router-link>
              <router-link :to="{ name: 'console' }" class="user-item">接口联调台</router-link>
              <button class="user-item user-item--seal" @click="doLogout">退出登录</button>
            </div>
          </div>
        </div>
      </header>

      <!-- ★ 载入失败横条：
           后端重启的几十秒里 /api/books 会失败，表现是"账本没了、各页全空"。
           不加这条的话用户会以为数据丢了（实际库里都在）。
           这里明说原因并给重试，比让页面静默变空好。 -->
      <div v-if="bookStore.loadError" class="loadbar">
        <span class="loadbar-mark">!</span>
        <span class="loadbar-text">
          账本没加载出来（{{ bookStore.loadError }}）。数据还在，重试一下就好。
        </span>
        <button class="loadbar-btn" :disabled="bookStore.loading" @click="retryLoad">
          {{ bookStore.loading ? '重试中…' : '重试' }}
        </button>
      </div>

      <main class="content">
        <router-view />
      </main>
    </div>
  </div>

  <!-- ==================== 新建账本 ==================== -->
  <div v-if="createBookOpen" class="mask" @click.self="createBookOpen = false">
    <div class="dialog">
      <div class="dialog-head">
        <span>新建账本</span>
        <button class="dialog-x" @click="createBookOpen = false">×</button>
      </div>
      <p class="dialog-tip">账本用来分开记不同的账，比如「日常」和「旅行」。</p>
      <label class="dialog-field">
        <span class="k">名称</span>
        <input
          v-model="newBookName"
          class="naw-input"
          maxlength="20"
          placeholder="如 旅行账本"
          autofocus
          @keyup.enter="doCreateBook"
          @keyup.esc="createBookOpen = false"
        />
      </label>
      <div class="dialog-foot">
        <button class="naw-textbtn" @click="createBookOpen = false">取消</button>
        <button class="naw-btn" :disabled="savingBook" @click="doCreateBook">
          {{ savingBook ? '创建中…' : '创建并切换' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--naw-paper);
}

.side {
  width: var(--naw-sidebar-w);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  padding: 0 0 12px;
  border-right: 1px solid var(--naw-rule);
}

.brand {
  height: var(--naw-topbar-h);
  display: flex;
  align-items: center;
  padding: 0 var(--naw-s5);
  border-bottom: 1px solid var(--naw-rule);
}

.brand-name {
  font-family: var(--naw-serif);
  font-size: 17px;
  letter-spacing: 0.14em;
}

.book-wrap {
  position: relative;
  z-index: 150;
  padding: 14px 14px 0;
}

.book-switch {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 10px;
  background: transparent;
  border: 1px solid var(--naw-rule);
  border-radius: 2px;
  cursor: pointer;
  font-size: var(--naw-fs-base);
  color: var(--naw-ink-2);
  transition: border-color 0.14s;
}

.book-switch:hover {
  border-color: var(--naw-rule-2);
}

.book-name {
  flex: 1;
  text-align: left;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.book-caret {
  font-size: 9px;
  color: var(--naw-ink-3);
}

/* ★ 下拉菜单必须盖住下面的导航，否则账本名和"记账台/账单"叠在一起看不清。
 *  之前 z-index 只有 30、且父级 .book-wrap 是普通流，菜单被后面的 .nav
 *  压住了（用户截图反馈"下拉挡住了导航"）。
 *  这里做两件事：抬高层级 + 给父级建立层叠上下文。 */
.book-menu {
  position: absolute;
  z-index: 200;
  top: calc(100% + 3px);
  left: 14px;
  right: 14px;
  background: var(--naw-paper);
  border: 1px solid var(--naw-rule-2);
  /* 下拉浮在内容之上时给一点投影，明确指出"这是一层浮起来的菜单"，
     否则在纯纸上它会和下面的导航糊成一片 */
  box-shadow: 0 2px 8px rgba(28, 25, 23, 0.06);
}

.book-item {
  display: block;
  width: 100%;
  text-align: left;
  padding: 7px 10px;
  background: none;
  border: none;
  font-size: var(--naw-fs-base);
  color: var(--naw-ink-2);
  cursor: pointer;
}

.book-item:hover {
  background: var(--naw-paper-2);
}

.book-item--on {
  color: var(--naw-ink);
  box-shadow: inset 2px 0 0 var(--naw-seal);
}

.book-empty {
  padding: 10px;
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}

/* ★ 新建入口：与账本项同一列表，用分隔线和加号区分"动作"与"切换" */
.book-item--add {
  border-top: 1px solid var(--naw-rule);
  color: var(--naw-ink-2);
}

.book-item--add:hover {
  color: var(--naw-ink);
}

.book-item--add .plus {
  margin-right: 4px;
  font-weight: 600;
}

/* ==================== 新建账本弹层 ==================== */
.mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(28, 25, 23, 0.28);
  display: grid;
  place-items: center;
}

.dialog {
  width: 340px;
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

.dialog-x {
  margin-left: auto;
  background: none;
  border: none;
  font-size: 18px;
  line-height: 1;
  color: var(--naw-ink-3);
  cursor: pointer;
}

.dialog-x:hover {
  color: var(--naw-ink);
}

.dialog-tip {
  margin: 12px 0 0;
  font-size: var(--naw-fs-sm);
  line-height: 1.7;
  color: var(--naw-ink-3);
}

.dialog-field {
  display: block;
  margin-top: 14px;
}

.dialog-field .k {
  display: block;
  font-size: var(--naw-fs-xs);
  letter-spacing: 0.12em;
  color: var(--naw-ink-3);
  margin-bottom: 5px;
}

.dialog-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
  margin-top: 18px;
  padding-top: 12px;
  border-top: 1px solid var(--naw-rule);
}

.nav {
  display: flex;
  flex-direction: column;
  margin-top: 18px;
  padding: 0 14px;
  transition: margin-top 0.16s ease;
}

/* 下拉展开时把导航推下去，避免菜单压在导航文字上。
   值要 ≥ 菜单高度（账本项 34px + 新建项 34px + 分隔 ≈ 72px），
   这里取 76px 留一点余量；账本多时菜单更高，但那些账本项本身也是可点的，
   压住下部导航的观感问题远小于"菜单项被导航遮挡"。 */
.nav--pushed {
  margin-top: 94px;
}

.nav-item {
  padding: 7px 12px;
  font-size: var(--naw-fs-md);
  color: var(--naw-ink-2);
  border-left: 2px solid transparent;
  transition:
    color 0.14s,
    border-color 0.14s;
}

.nav-item:hover {
  color: var(--naw-ink);
}

.nav-item--on {
  color: var(--naw-ink);
  border-left-color: var(--naw-seal);
}

.side-foot {
  margin-top: auto;
  padding: 12px 14px 0;
  border-top: 1px solid var(--naw-rule);
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.topbar {
  height: var(--naw-topbar-h);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  padding: 0 var(--naw-page-x);
  border-bottom: 1px solid var(--naw-rule);
}

.page-title {
  margin: 0;
  font-size: var(--naw-fs-lg);
  font-weight: 400;
  letter-spacing: 0.04em;
}

.top-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 22px;
}

.top-kv {
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}

.top-kv b {
  font-weight: 400;
  color: var(--naw-ink-2);
  margin-left: 5px;
}

.user-wrap {
  position: relative;
}

.user-chip {
  background: none;
  border: none;
  border-bottom: 1px solid var(--naw-ink-3);
  padding: 2px 0;
  font-size: var(--naw-fs-base);
  color: var(--naw-ink-2);
  cursor: pointer;
}

.user-chip:hover {
  color: var(--naw-ink);
  border-bottom-color: var(--naw-ink);
}

.user-menu {
  position: absolute;
  z-index: 30;
  top: calc(100% + 8px);
  right: 0;
  min-width: 132px;
  background: var(--naw-paper);
  border: 1px solid var(--naw-rule-2);
}

.user-item {
  display: block;
  width: 100%;
  text-align: left;
  padding: 7px 12px;
  background: none;
  border: none;
  font-size: var(--naw-fs-base);
  color: var(--naw-ink-2);
  cursor: pointer;
}

.user-item:hover {
  background: var(--naw-paper-2);
}

.user-item--seal {
  color: var(--naw-seal);
  border-top: 1px solid var(--naw-rule);
}

.content {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 载入失败横条：用印章红 + 底色，比 Element 的 warning 更像账页自己的话 */
.loadbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 20px;
  background: #fbeeea;
  border-bottom: 1px solid #f0d4cc;
  font-size: 13px;
  color: var(--naw-ink);
}

.loadbar-mark {
  flex: none;
  width: 16px;
  height: 16px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--naw-seal);
  color: #fff;
  font-size: 11px;
  line-height: 1;
}

.loadbar-text {
  flex: 1;
  min-width: 0;
}

.loadbar-btn {
  flex: none;
  padding: 3px 11px;
  border: 1px solid var(--naw-seal);
  border-radius: 4px;
  background: transparent;
  color: var(--naw-seal);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
}

.loadbar-btn:hover:not(:disabled) {
  background: var(--naw-seal);
  color: #fff;
}

.loadbar-btn:disabled {
  opacity: 0.55;
  cursor: default;
}
</style>
