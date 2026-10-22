package com.sakuya.notesatwill.entity.DTO;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建 / 修改角色设定的请求。
 *
 * <p><b>★★ 两种创建方式，靠 {@code presetId} 区分</b>：
 * <pre>
 *   从预设创建：  { "presetId": 3001 }
 *                 → 把预设 3001 的字段【复制】过来；此时 name 可以不传
 *
 *   空白创建：    { "name": "我的小助手", "gender": "FEMALE", ... }
 *                 → name 必填
 *
 *   预设 + 覆盖：  { "presetId": 3001, "name": "鹦鹉改名了" }
 *                 → 先复制预设，再用请求里【非 null】的字段覆盖
 * </pre>
 *
 * <p><b>★ 为什么是"复制"而不是"引用"</b>：
 * 若只存 {@code presetId}、显示时去读预设表，那用户改名字就会改到所有人共用的预设上。
 * 复制一份后，预设永远只读，用户改的只是自己那份。
 * {@code presetId} 保留下来仅作<b>溯源</b>（"这个角色从哪来"），不参与读取。
 *
 * <p><b>★ 所有长度限制都对应数据库列宽</b>（name/relation/address 是 20，
 * personality 是 200）。<b>必须在 Service 层之前拦住</b> —— 否则 MySQL 会
 * 静默截断（非严格模式）或报错（严格模式），两种都很糟。
 *
 * @param presetId         预设 id；有值时先复制该预设
 * @param name             角色名字（≤20 字）；空白创建时必填
 * @param avatar           头像 URL
 * @param gender           性别：MALE / FEMALE / OTHER
 * @param relation         与用户的关系（朋友 / 管家 / 学姐 / 宠物…，≤20 字）
 * @param address          对用户的称呼（主人 / 老板 / 铲屎官…，≤20 字）
 * @param personality      角色设定（≤200 字）
 * @param chatReplyEnabled 是否允许主动发言（1/0）；不传默认 0
 * @author sakuya
 */
public record PersonaRequest(
        Long presetId,

        @Size(max = 20, message = "角色名字不能超过 20 个字")
        String name,

        String avatar,

        @Pattern(regexp = "^(MALE|FEMALE|OTHER)$", message = "性别只能是 MALE / FEMALE / OTHER")
        String gender,

        @Size(max = 20, message = "关系描述不能超过 20 个字")
        String relation,

        @Size(max = 20, message = "称呼不能超过 20 个字")
        String address,

        @Size(max = 200, message = "角色设定不能超过 200 个字")
        String personality,

        Integer chatReplyEnabled) {
}
