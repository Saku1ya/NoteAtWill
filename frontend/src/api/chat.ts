/**
 * AI 伙伴对话接口 `/api/chat`，含 **SSE 推送**。
 *
 * ★★ SSE 最大的限制：**原生 `EventSource` 不能自定义请求头**，
 *   所以我们没法像 Axios 那样塞 `Authorization: Bearer xxx`。
 *
 * 三种解法对比：
 * | 方案 | 做法 | 代价 |
 * |---|---|---|
 * | A. Cookie 鉴权 | 登录时同时下发 HttpOnly Cookie | **要改后端** |
 * | B. URL 传 token | `/stream?token=xxx` | token 会进服务器日志/浏览器历史 |
 * | C. fetch + ReadableStream | 自己解析 SSE 格式 | 复杂，且失去自动重连 |
 *
 * ★ 本文件采用 **方案 C 的变体**：用 `fetch` 手动解析 SSE。
 *   理由：
 *     1. **不用改后端**（方案 A 要动鉴权）
 *     2. **不把 token 放进 URL**（方案 B 有泄露风险，且会进 Nginx access log）
 *     3. 自动重连可以自己实现，逻辑并不复杂
 *
 *   代价是要自己写解析，但 SSE 格式本身很简单（`event:` / `data:` / 空行分隔）。
 */
import { getToken } from './request'
import type { ChatMessagePageVO, DeletionVO, GreetingVO } from './types'

// ============================================================================
// 普通 REST
// ============================================================================

import http from './request'

/**
 * 拉取对话历史（**游标分页，倒序**）。
 *
 * ★ 不用 `page` 而用 `cursor`：对话是持续追加的场景，
 *   offset 分页在"来新消息"时会重复，且越翻越慢。
 *
 * @param cursor 只取 id 小于它的消息；**首次加载不传**
 * @param size   每页条数，默认 20，上限 100
 */
export function pageMessages(
  cursor?: number | null,
  size = 20,
  bookId?: number | null,
) {
  return http.get<never, ChatMessagePageVO>('/chat/messages', {
    // ★ bookId 不传时后端保持"用户级"的旧行为；传了就只返回该账本的消息
    params: { cursor: cursor ?? undefined, size, bookId: bookId ?? undefined },
  })
}

/**
 * 每日首次打开：生成（或复用）今天的问候。
 *
 * ★★ **幂等**：同一天连续调多次只会有一条问候。
 *   返回 `alreadySent=true` 表示今天早就有问候了 → **前端不要重复播报**。
 * ★ `degraded=true` 表示 AI 不可用、用了模板兜底（内容照常展示）。
 */
export function dailyOpen() {
  // ★ 3 秒超时 + 缓冲：后端写超时是 3s，这里给 10s 应对首次连接开销
  return http.post<never, GreetingVO>('/chat/daily-open', undefined, { timeout: 10_000 })
}

/**
 * 删除一条消息。
 *
 * ★★★ `cascadeBill` 决定这是**两种完全不同量级的操作**：
 * ```
 *   false（默认）→ 只删消息，账单保留    ← 安全
 *   true         → 连账单一起删，**余额会被回滚**  ← 破坏性
 * ```
 * 前端必须：
 *   1. 默认 `false`
 *   2. `true` 时弹二次确认，**明确写出"会删除账单并回滚余额"**
 *   3. 把入口藏起来（放"更多"菜单里），不要做成主按钮
 */
export function deleteMessage(id: number, cascadeBill = false) {
  return http.delete<never, DeletionVO>(`/chat/messages/${id}`, {
    params: { cascadeBill },
  })
}

// ============================================================================
// SSE
// ============================================================================

/** SSE 事件：账单卡片已创建（记账后推送）。 */
export interface BillCreatedEvent {
  messageId: number
  billId: number
  bookId: number
  amount: string
  type: 'EXPENSE' | 'INCOME'
  categoryId: number
  /** 格式：`yyyy-MM-dd HH:mm:ss`（与项目全局时间格式一致） */
  billTime: string
  remark: string | null
  content: string
}

export interface SseHandlers {
  /** 收到账单卡片推送。 */
  onBillCreated?: (payload: BillCreatedEvent) => void
  /** 连接建立（可用于把 UI 从"连接中"切到"已连接"）。 */
  onOpen?: () => void
  /** 连接断开（会触发自动重连）。 */
  onClose?: (reason?: string) => void
  /** 底层错误。 */
  onError?: (err: unknown) => void
}

export interface SseConnection {
  /** 主动关闭（组件卸载时**必须**调用，否则连接泄漏）。 */
  close: () => void
  /** 当前是否已连接。 */
  isOpen: () => boolean
}

/**
 * 建立 SSE 连接。
 *
 * ★★ 用 `fetch` + `ReadableStream` 手动解析，而不是 `EventSource`：
 *   因为 `EventSource` **不能带 Authorization 头**，而把 token 放 URL
 *   会泄露进服务器访问日志。详见文件头注释。
 *
 * ★ 自动重连：断线后按指数退避重连（1s → 2s → 4s，最多 30s）。
 *   **重连后务必让调用方重新拉一次最新消息** —— 断线期间的消息不会补发，
 *   不补拉就会出现"卡片消失了"的错觉。
 *
 * @param handlers 事件回调
 * @param baseUrl  API 前缀，默认 `/api`（走 Vite 代理）
 */
export function connectSse(handlers: SseHandlers, baseUrl = '/api'): SseConnection {
  const controller = new AbortController()
  let closed = false
  let opened = false
  let retryDelay = 1000
  let retryTimer: ReturnType<typeof setTimeout> | null = null

  const run = async (): Promise<void> => {
    if (closed) return

    try {
      const token = getToken()
      const resp = await fetch(`${baseUrl}/chat/stream`, {
        method: 'GET',
        headers: {
          Accept: 'text/event-stream',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        signal: controller.signal,
      })

      if (!resp.ok || !resp.body) {
        throw new Error(`SSE 连接失败：HTTP ${resp.status}`)
      }

      opened = true
      retryDelay = 1000
      handlers.onOpen?.()

      const reader = resp.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      let eventName = ''
      let dataLines: string[] = []

      // eslint-disable-next-line no-constant-condition
      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        // SSE 以 **空行** 分隔事件
        let idx: number
        while ((idx = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, idx).replace(/\r$/, '')
          buffer = buffer.slice(idx + 1)

          if (line === '') {
            // 一个事件结束
            if (eventName && dataLines.length > 0) {
              dispatch(eventName, dataLines.join('\n'), handlers)
            }
            eventName = ''
            dataLines = []
            continue
          }
          if (line.startsWith(':')) continue // 注释/心跳
          if (line.startsWith('event:')) {
            eventName = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).trimStart())
          }
        }
      }
    } catch (err) {
      // ★ 主动关闭导致的 abort 不算错误
      if (!closed) {
        handlers.onError?.(err)
      }
    } finally {
      if (opened) {
        opened = false
        handlers.onClose?.()
      }
      // 自动重连（指数退避，上限 30 秒）
      if (!closed) {
        retryTimer = setTimeout(() => {
          void run()
        }, retryDelay)
        retryDelay = Math.min(retryDelay * 2, 30_000)
      }
    }
  }

  void run()

  return {
    close: () => {
      closed = true
      if (retryTimer) clearTimeout(retryTimer)
      controller.abort()
    },
    isOpen: () => opened,
  }
}

/** 解析并分发单个 SSE 事件。 */
function dispatch(name: string, data: string, handlers: SseHandlers): void {
  if (name !== 'bill-created') {
    // 未知事件：忽略（后端将来可能加新事件，不要让前端崩）
    return
  }
  try {
    const payload = JSON.parse(data) as BillCreatedEvent
    handlers.onBillCreated?.(payload)
  } catch (err) {
    handlers.onError?.(err)
  }
}
