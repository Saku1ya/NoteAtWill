package com.sakuya.notesatwill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sakuya.notesatwill.entity.AiPersona;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户角色 Mapper。
 *
 * <p><b>★★ 本表有一条"业务不变量"：同一用户<b>最多一条</b> {@code is_active = 1}</b>。
 * 数据库层面<b>无法用唯一索引强制</b>：
 * <ul>
 *   <li>{@code UNIQUE(user_id, is_active)} 不行 —— 多条 {@code is_active = 0} 会互相冲突</li>
 *   <li>MySQL 不支持"部分唯一索引"（PostgreSQL 可以）</li>
 * </ul>
 * 所以这条不变量只能靠 Service 层保证：<b>任何把某条置为 active 的操作，
 * 都必须在同一事务里把该用户其它角色置为 inactive</b>。
 * 见 {@code PersonaService#activate} 与 {@code #create}。
 *
 * @author sakuya
 */
@Mapper
public interface AiPersonaMapper extends BaseMapper<AiPersona> {
}
