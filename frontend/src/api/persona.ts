/**
 * 角色设定接口 `/api/persona`。
 *
 * ★★ 核心约束：**用户可以有多条角色，但同一时间只有一条生效**。
 *   - 切换生效只能走 `activate`（内部会取消其它角色的激活）
 *   - **修改角色不会改变"哪个在生效"**
 *   - 删除正在使用的角色后**不会自动切换到别的**（`active` 会变成 null）
 */
import http from './request'
import type { PersonaPresetVO, PersonaRequest, PersonaVO } from './types'

/** 查系统预设角色（5 个，**全局只读**）。 */
export function listPresets() {
  return http.get<never, PersonaPresetVO[]>('/persona/presets')
}

/** 查我的全部角色（**激活的排最前**）。 */
export function listMyPersonas() {
  return http.get<never, PersonaVO[]>('/persona')
}

/**
 * 查当前生效的角色。
 *
 * ★★ **可能返回 null**（还没建角色，或刚删掉正在用的那个）——
 *   前端要能处理"无角色"状态，而不是假设一定有。
 */
export function getActivePersona() {
  return http.get<never, PersonaVO | null>('/persona/active')
}

/**
 * 创建角色。
 *
 * ```
 * 从预设复制：  { presetId: 3001 }
 * 空白创建：    { name: '我的小助手', gender: 'OTHER' }
 * 预设 + 覆盖：  { presetId: 3001, name: '改名了' }
 * ```
 *
 * ★ 预设是**复制**而不是引用 —— 用户改自己的角色不影响全局预设。
 * ★ 用户还没有任何激活角色时，新建的**自动生效**；已有则不改当前生效的那个。
 */
export function createPersona(payload: PersonaRequest) {
  return http.post<never, PersonaVO>('/persona', payload)
}

/** 修改角色（**整体替换**；`isActive` 不受影响）。 */
export function updatePersona(id: number, payload: PersonaRequest) {
  return http.put<never, PersonaVO>(`/persona/${id}`, payload)
}

/** 切换当前生效的角色（**唯一入口**，会取消其它角色的激活）。 */
export function activatePersona(id: number) {
  return http.put<never, PersonaVO>(`/persona/${id}/activate`)
}

/** 删除角色（**不自动切换**；删的是生效角色时 `active` 会变 null）。 */
export function deletePersona(id: number) {
  return http.delete<never, void>(`/persona/${id}`)
}
