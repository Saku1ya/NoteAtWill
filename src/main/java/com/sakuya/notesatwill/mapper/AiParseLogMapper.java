package com.sakuya.notesatwill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sakuya.notesatwill.entity.AiParseLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 解析记录 Mapper。
 *
 * <p>本表只被 AI 模块使用，没有自定义 SQL —— 全部走 MyBatis-Plus 的默认能力。
 * 注意 {@code t_ai_parse_log} <b>没有 {@code deleted} 字段</b>：
 * 它是"操作日志"而不是业务数据，用 {@code status} 表达生命周期
 * （PENDING → CONFIRMED / REJECTED），不需要逻辑删除。
 *
 * @author sakuya
 */
@Mapper
public interface AiParseLogMapper extends BaseMapper<AiParseLog> {
}
