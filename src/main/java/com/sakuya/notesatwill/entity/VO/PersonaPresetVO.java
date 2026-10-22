package com.sakuya.notesatwill.entity.VO;

/**
 * 系统预设角色（{@code t_persona_preset}，<b>全局只读</b>）。
 *
 * <p><b>★ 前端用这个列表让用户"挑一个"</b>，选中后调
 * {@code POST /api/persona { "presetId": 3001 }} 复制成自己的角色。
 *
 * <p><b>★ 预设永远不可写</b>：没有 {@code userId} 字段意味着它不属于任何人，
 * 属于所有人 —— 任何"修改预设"的接口都会影响全部用户。
 * 用户想改，就改自己复制出来的那一份。
 *
 * @param id          预设 id
 * @param name        名字
 * @param avatar      头像
 * @param gender      性别
 * @param relation    与用户的关系
 * @param address     对用户的称呼
 * @param personality 角色设定
 * @param sort        展示顺序（升序）
 * @author sakuya
 */
public record PersonaPresetVO(
        Long id,
        String name,
        String avatar,
        String gender,
        String relation,
        String address,
        String personality,
        Integer sort) {
}
