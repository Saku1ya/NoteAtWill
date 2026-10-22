/**
 * 用户接口 `/api/user`。
 *
 * ★★ `changePassword` 的请求体是 **`{code, newPassword}`** ——
 *   **用的是短信验证码，不是旧密码**。`ChangePasswordRequest` 里根本没有 oldPassword 字段。
 *   （这是项目里最容易凭直觉写错的一个接口，写 UI 时注意文案要写"获取验证码"而不是"输入旧密码"。）
 */
import http from './request'
import type { UserVO } from './types'

export function getMe() {
  return http.get<never, UserVO>('/user/me')
}

/**
 * 修改密码（已登录）。
 *
 * @param code 短信验证码（**不是旧密码**）
 * @param newPassword 新密码，6~32 位
 */
export function changePassword(code: string, newPassword: string) {
  return http.put<never, void>('/user/password', { code, newPassword })
}
