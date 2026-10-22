package com.sakuya.notesatwill.entity.VO;

import java.util.List;

/**
 * 对话消息的一页（<b>游标分页</b>）。
 *
 * <p><b>★★ 为什么这个接口必须是游标分页，不能是 offset 分页</b>
 * <pre>
 *   用户正在看第 2 页（offset=20），此时来了 3 条新消息
 *
 *   ❌ offset 分页：第 2 页的起点从 id=80 滑到了 id=77
 *      → 用户看到的消息【重复出现】
 *      → 而且越翻越慢（OFFSET 10000 要扫过一万行）
 *
 *   ✅ 游标分页：按 id 定位"比这个 id 更早的 20 条"
 *      → 新消息不会挤动已经翻过的位置
 *      → WHERE id < ? 走主键，翻到第几页都是同样的速度
 * </pre>
 * 对话是「持续追加」的场景，<b>游标分页不是优化，是正确性问题</b>。
 *
 * <p><b>★ {@code nextCursor} 的语义</b>：
 * 传回的值是"本页最后一条（最旧一条）的 id"。客户端翻下一页时原样回传，
 * 表示"我要比这个更早的消息"。
 * <b>没有更多时返回 null</b> —— 前端据此停止加载，不必再发一次空请求。
 *
 * @param items      本页消息（<b>按 id 倒序</b>，最新的在最前）
 * @param nextCursor 下一页游标；无更多数据时为 {@code null}
 * @param hasMore    是否还有更早的消息
 * @author sakuya
 */
public record ChatMessagePageVO(
        List<ChatMessageVO> items,
        Long nextCursor,
        boolean hasMore) {
}
