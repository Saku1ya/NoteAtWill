/**
 * 全局用户与账本状态。
 *
 * ★ 为什么用 Pinia 而不是到处传 props：
 *   账本 ID 几乎每个页面都要用（账单、统计、预算、账户都按账本隔离），
 *   放 store 里一处维护，切账本时全站跟着变。
 *
 * ★ token 不放进 store 的持久化：
 *   token 的生命周期比 store 长（刷新页面 store 重建），所以 token 归
 *   `api/request.ts` 直接管 localStorage，store 只缓存"当前是谁"。
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getMe } from '../api/user'
import type { UserVO } from '../api/types'
import { listBooks } from '../api/base'
import type { BookVO } from '../api/types'
import { clearToken, getToken } from '../api/request'

const BOOK_KEY = 'naw_book_id'

export const useUserStore = defineStore('user', () => {
  const token = ref<string | null>(getToken())
  const user = ref<UserVO | null>(null)
  const loading = ref(false)

  const isLoggedIn = computed(() => !!token.value && !!user.value)
  const isMember = computed(() => (user.value?.memberLevel ?? 0) > 0)
  const nickname = computed(() => user.value?.nickname || user.value?.phone || '我')

  /** 拉当前用户。★ 登录接口不返回 user，必须单独请求一次。 */
  async function fetchUser(): Promise<UserVO | null> {
    if (!getToken()) return null
    loading.value = true
    try {
      user.value = await getMe()
      token.value = getToken()
      return user.value
    } catch {
      user.value = null
      return null
    } finally {
      loading.value = false
    }
  }

  function setTokenAndCache(t: string) {
    token.value = t
  }

  function reset() {
    clearToken()
    token.value = null
    user.value = null
  }

  return { token, user, loading, isLoggedIn, isMember, nickname, fetchUser, setTokenAndCache, reset }
})

export const useBookStore = defineStore('book', () => {
  const books = ref<BookVO[]>([])
  const currentId = ref<number | null>(null)
  const loading = ref(false)
  /** 上一次载入失败的原因。空字符串表示正常。★ 不抛异常，靠它让界面说话。 */
  const loadError = ref('')

  const current = computed(() => books.value.find((b) => b.id === currentId.value) ?? null)

  /**
   * 载入账本列表，并恢复上次选中的账本（不存在则取第一个）。
   *
   * ★ 返回是否成功，且**不向外抛异常**：
   *   调用方（App / 布局）需要区分"没有账本"和"没拿到账本"，
   *   抛出去会让启动流程直接中断，页面停在半截状态。
   */
  async function fetchBooks(): Promise<boolean> {
    loading.value = true
    try {
      books.value = await listBooks()
      const saved = Number(localStorage.getItem(BOOK_KEY))
      const hit = books.value.find((b) => b.id === saved)
      if (hit) currentId.value = hit.id
      else if (books.value.length) currentId.value = books.value[0].id
      else currentId.value = null
      loadError.value = ''
      return true
    } catch (e) {
      // ★ 失败时**不清空**已有列表：切账本失败不该把界面变空
      loadError.value = e instanceof Error ? e.message : '账本加载失败'
      return false
    } finally {
      loading.value = false
    }
  }

  function select(id: number) {
    currentId.value = id
    localStorage.setItem(BOOK_KEY, String(id))
  }

  function reset() {
    books.value = []
    currentId.value = null
    loadError.value = ''
  }

  return { books, currentId, current, loading, loadError, fetchBooks, select, reset }
})
