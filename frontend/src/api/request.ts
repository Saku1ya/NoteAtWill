/**
 * Axios 实例与拦截器。
 *
 * ★★ 本文件是全前端最关键的样板代码，做三件事：
 *   1. **请求**：自动带上 `Authorization: Bearer <token>`
 *   2. **响应**：剥掉 `Result` 外壳，页面直接拿业务数据
 *   3. **错误**：统一处理「业务错误（HTTP 200 + code≠200）」与「未登录（HTTP 401）」
 *
 * ★★★ 最容易搞错的地方：**业务错误也是 HTTP 200**。
 *   后端约定：任何业务失败都返回 `{code: 4000, message: "账单不存在"}` 且 HTTP 200。
 *   所以拦截器里不能只判 `response.status === 200` 就当作成功，
 *   **必须再看 `data.code`**。只看 HTTP 状态会把所有业务错误当成成功。
 */
import axios, { AxiosError } from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import type { Result } from './types'

/** token 在 localStorage 里的键名（只用这一个常量，避免各处写错字符串）。 */
export const TOKEN_KEY = 'naw_token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

/**
 * 业务异常：`code !== 200` 时抛出。
 *
 * ★ 用专门的类而不是普通 `Error`，是为了让调用方能区分：
 *   - `BusinessError`：业务规则拒绝（如"分类不存在"）→ 提示用户
 *   - 其他异常：网络/代码问题 → 提示"稍后重试"
 */
export class BusinessError extends Error {
  /** ★ 不用构造函数参数属性（`public readonly code`）：
   *  tsconfig 开了 `erasableSyntaxOnly`，那种写法属于"非可擦除语法"，会直接报错。 */
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'BusinessError'
    this.code = code
  }
}

const http = axios.create({
  // ★ 用相对路径，走 Vite 代理（vite.config.ts 里把 /api 转发到 8080）。
  //   这样开发期**不会**有 CORS 问题，也避免把后端地址硬编码进代码。
  baseURL: '/api',
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json; charset=utf-8' },
})

/** 请求拦截器：自动加 token。 */
http.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = getToken()
    if (token) {
      config.headers.set('Authorization', `Bearer ${token}`)
    }
    return config
  },
  (error) => Promise.reject(error),
)

/**
 * 响应拦截器：剥壳 + 分流错误。
 *
 * ★ 返回的是 **`data` 里的业务数据**，不是 AxiosResponse。
 *   所以类型断言成 `T` 是安全的（调用方拿到的就是业务对象）。
 */
http.interceptors.response.use(
  (response: AxiosResponse<Result>) => {
    const body = response.data

    // 有些接口（如登出）可能返回非 Result 结构，防御性判断
    if (body === null || body === undefined || typeof body !== 'object' || !('code' in body)) {
      return response.data as never
    }

    if (body.code === 200) {
      // 成功 → 直接返回业务数据（剥掉 code/message 外壳）
      return body.data as never
    }

    // ★ 业务失败（HTTP 仍是 200）→ 抛 BusinessError
    throw new BusinessError(body.code, body.message || '操作失败')
  },
  (error: AxiosError<Result>) => {
    // ---------- HTTP 层错误 ----------
    const status = error.response?.status

    if (status === 401) {
      // ★★ 只有未登录/过期才走这里（业务错误不会返回 401）
      clearToken()
      // 用 location 跳转而不是 router.push：此时可能还没拿到 router 实例，
      // 而且要确保刷新页面状态（清掉内存里的旧用户信息）
      if (!location.pathname.startsWith('/login')) {
        location.href = '/login'
      }
      return Promise.reject(new BusinessError(401, '登录已过期，请重新登录'))
    }

    // 后端错误若带了 Result 结构，优先用它的 message
    const body = error.response?.data
    if (body && typeof body === 'object' && 'code' in body) {
      return Promise.reject(new BusinessError(body.code, body.message || '请求失败'))
    }

    if (error.code === 'ECONNABORTED') {
      return Promise.reject(new BusinessError(-1, '请求超时，请稍后重试'))
    }
    if (!error.response) {
      return Promise.reject(new BusinessError(-1, '网络异常，请检查后端服务是否启动'))
    }
    return Promise.reject(new BusinessError(status ?? -1, error.message || '请求失败'))
  },
)

export default http
