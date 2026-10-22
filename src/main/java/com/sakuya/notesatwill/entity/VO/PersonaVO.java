package com.sakuya.notesatwill.entity.VO;

import java.time.LocalDateTime;

/**
 * 用户角色设定（{@code t_ai_persona}）。
 *
 * <p><b>★★ {@code isActive} 的语义（本模块的核心约束）</b>：
 * <b>同一用户最多只有一条 {@code isActive = true}</b>。
 * 用户可以有多个角色，但同一时间只有一个在生效 ——
 * AI 伙伴对话、每日问候都读"当前激活的那个"。</p>
 *
 * <p>切换激活走 {@code PUT /api/persona/{id}/activate}，
 * 该操作会把其它角色的 {@code is_active} 置 0。
 *
 * <p><b>★ {@code presetId} 只是溯源信息</b>：表示"这个角色是从哪个预设复制来的"，
 * <b>不参与任何读取</b>。用户改自己的角色不会影响 {@code t_persona_preset}
 * （那是所有人共享的只读数据）。
 *
 * @param id               角色 id
 * @param presetId         来源预设 id；空白创建时为 null
 * @param name             角色名字
 * @param avatar           头像
 * @param gender           性别：MALE / FEMALE / OTHER
 * @param relation         与用户的关系
 * @param address          对用户的称呼
 * @param personality      角色设定
 * @param chatReplyEnabled 是否允许主动发言
 * @param isActive         <b>是否为当前生效角色</b>（同一用户至多一个 true）
 * @param createTime       创建时间
 * @param updateTime       更新时间
 * @author sakuya
 */
public record PersonaVO(
        Long id,
        Long presetId,
        String name,
        String avatar,
        String gender,
        String relation,
        String address,
        String personality,
        Boolean chatReplyEnabled,
        Boolean isActive,
        LocalDateTime createTime,
        LocalDateTime updateTime) {
}
