package com.sakuya.notesatwill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sakuya.notesatwill.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话消息 Mapper。
 *
 * <p><b>★ 没有自定义 SQL</b>：游标分页用 {@code LambdaQueryWrapper + Page}
 * （{@code searchCount=false}）实现，这样 {@code @TableLogic} 会自动补
 * {@code deleted = 0}，不必手写 —— 手写 {@code @Select} 时很容易漏掉这个条件，
 * 导致"已删除的消息又冒出来"。
 *
 * <p><b>★ 索引已就绪</b>：{@code idx_user_msg(user_id, deleted, id)} 是
 * {@code WHERE user_id = ? AND deleted = 0 AND id < ? ORDER BY id DESC}
 * 的完美覆盖索引 —— <b>游标分页的查询代价与翻到第几页无关</b>。
 * 这也是为什么本表不需要额外的"分页优化"。
 *
 * @author sakuya
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
