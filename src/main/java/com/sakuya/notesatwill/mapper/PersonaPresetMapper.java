package com.sakuya.notesatwill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sakuya.notesatwill.entity.PersonaPreset;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色<b>预设</b> Mapper（系统级，全局共享）。
 *
 * <p><b>★ 本表是只读的</b>：{@code t_persona_preset} <b>没有 {@code user_id} 列</b>，
 * 是 5 条系统预置数据，所有用户共用。所以：
 * <ul>
 *   <li>只提供查询，<b>不提供任何写入方法</b></li>
 *   <li>用户"选择预设"时是把它<b>复制</b>到 {@code t_ai_persona}，
 *       而不是引用 —— 否则用户改自己的角色名会改到所有人都能看到的那条预设</li>
 * </ul>
 *
 * <p><b>★ 也没有 {@code deleted} 列</b>：预设是随代码发布的固定数据，
 * 不做逻辑删除（要下线某个预设直接改 seed 脚本）。
 *
 * @author sakuya
 */
@Mapper
public interface PersonaPresetMapper extends BaseMapper<PersonaPreset> {
}
