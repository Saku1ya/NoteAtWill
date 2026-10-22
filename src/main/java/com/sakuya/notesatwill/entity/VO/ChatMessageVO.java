package com.sakuya.notesatwill.entity.VO;

import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 一条对话消息。
 *
 * <p><b>★ {@code meta} 是真正的 JSON 对象，不是字符串</b>：
 * {@code t_chat_message.meta_json} 里存的是卡片数据（如
 * {@code {"amount": 100, "bookName": "日常"}}）。
 * 若把它原样当字符串返回，前端会收到
 * {@code "meta": "{\"amount\":100}"} —— 还得再 {@code JSON.parse} 一次，
 * 而且双层转义很容易出错。
 * <b>在服务端解析成 {@link JsonNode} 再输出，前端拿到的直接是嵌套对象。</b>
 *
 * <p><b>★ 为什么不返回 {@code deleted} / {@code userId}</b>：
 * 前者是内部状态（逻辑删除的消息根本不会出现在结果里），
 * 后者由 token 决定、返回给前端毫无用处且多一处信息泄露面。
 *
 * @param id          消息 id（同时充当游标）
 * @param bookId      统计卡片记录当时的账本口径；普通文本消息为 null
 * @param role        发送方：USER / AI / SYSTEM
 * @param type        消息类型：TEXT / BILL_CARD / STAT_CARD / GREETING
 * @param content     文本内容；卡片类消息可能为 null
 * @param billId      BILL_CARD 时指向的账单 id；其它类型为 null
 * @param meta        卡片附加信息（JSON 对象）；无附加信息时为 null
 * @param triggerType 触发来源：MANUAL_BILL / AI_PARSE / DAILY_OPEN
 * @param createTime  消息时间（前端据此做时间分隔）
 * @author sakuya
 */
public record ChatMessageVO(
        Long id,
        Long bookId,
        String role,
        String type,
        String content,
        Long billId,
        JsonNode meta,
        String triggerType,
        LocalDateTime createTime) {
}
