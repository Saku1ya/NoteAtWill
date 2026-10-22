/**
 * 认证接口 `/api/auth`。
 *
 * ★ 注意 `send-code` 有**频率限制**（Redis 里 60 秒一次），
 *   前端按钮必须做倒计时，否则用户连点会拿到"发送过于频繁"。
 */
import http from './request'
import type { TokenVO } from './types'

export function sendCode(phone: string) {
  return http.post<never, void>('/auth/send-code', { phone })
}

export function register(payload: {
  phone: string
  code: string
  password: string
  nickname?: string
  email?: string
}) {
  return http.post<never, TokenVO>('/auth/register', payload)
}

export function loginByPassword(phone: string, password: string) {
  return http.post<never, TokenVO>('/auth/login/password', { phone, password })
}

export function loginByCode(phone: string, code: string) {
  return http.post<never, TokenVO>('/auth/login/code', { phone, code })
}

/**
 * 忘记密码：**手机号 + 验证码 + 新密码**（不需要旧密码）。
 */
export function resetPassword(phone: string, code: string, newPassword: string) {
  return http.post<never, void>('/auth/reset-password', { phone, code, newPassword })
}

export function logout() {
  return http.post<never, void>('/auth/logout')
}
