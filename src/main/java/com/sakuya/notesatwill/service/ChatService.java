package com.sakuya.notesatwill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sakuya.notesatwill.entity.ChatMessage;
import com.sakuya.notesatwill.entity.VO.ChatMessagePageVO;
import com.sakuya.notesatwill.entity.VO.ChatMessageVO;
import com.sakuya.notesatwill.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话消息服务（<b>本阶段只做"读"</b>）。
 *
 * <p><b>★★ 为什么只做读、不做写</b>：
 * 写入方（用户发消息、记账后写卡片、每日问候）属于 D9 的 AI 伙伴功能。
 * 先让"读"跑通，D9 才有东西可读，也能先把游标分页这条最容易出错的路走通。
 * <b>提前做一个只有读的接口不是半成品，而是把风险最高的一块先验证掉。</b>
 *
 * <p><b>★ 本类不加 {@code @Transactional}</b>：只读，不涉及多表一致性。
 *
 * @author sakuya
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /** 默认每页条数 */
    private static final int DEFAULT_SIZE = 20;
    /** 每页上限：防止前端传 size=100000 把内存打爆 */
    private static final int MAX_SIZE = 100;

    private final ChatMessageMapper chatMessageMapper;
    private final ObjectMapper objectMapper;

    /**
     * 游标分页拉取对话历史（<b>倒序</b>：最新在前，向下翻是更早的消息）。
     *
     * <p><b>★★ 实现要点：多查一条来判断 hasMore</b>
     * <pre>
     *   想返回 20 条 → 实际 LIMIT 21
     *     查到 21 条 → 说明还有更多：丢掉第 21 条，hasMore = true
     *     查到 ≤20 条 → 到底了：全返回，hasMore = false
     * </pre>
     * 如果只查 20 条再判断，就必须额外发一条 COUNT 查询 ——
     * 而 COUNT 在游标分页里既慢又没必要（多查一行几乎零成本）。
     *
     * <p><b>★ {@code searchCount=false}</b>：MyBatis-Plus 的 {@code Page} 默认会
     * 自动执行一条 {@code SELECT COUNT(*)}，这里明确关掉 —— 我们<b>不需要总数</b>
     * （游标分页的整个设计前提就是"不关心总数"）。
     *
     * <p><b>★ 为什么 {@code orderByDesc(id)} 而不是 {@code createTime}</b>：
     * <ol>
     *   <li>id 是主键，与 {@code idx_user_msg(user_id, deleted, id)} 完全匹配</li>
     *   <li>同一秒内插入的多条消息，{@code create_time} 相同、顺序不确定，
     *       会导致"翻页时某条消息重复或漏掉"；id 单调递增，<b>顺序绝对稳定</b></li>
     * </ol>
     *
     * @param userId  当前登录用户
     * @param cursor  游标：只取 id <b>小于</b>它的消息；{@code null} 表示取最新的一页
     * @param size    每页条数（1~100，不传或越界会被修正）
     * @return 一页消息 + 下一页游标
     */
    public ChatMessagePageVO pageMessages(Long userId, Long cursor, Integer size) {
        return pageMessages(userId, null, cursor, size);
    }

    /**
     * 按账本拉取对话历史（<b>2026-10-20 新增 bookId</b>）。
     *
     * <p><b>★ 为什么现在要按账本过滤</b>：
     * 初版把对话设计成"用户级"的（不按账本切分），但实际用下来用户切到
     * 「旅行账本」后仍看到「日常账本」的记账卡片，观感是"数据串了"。
     * 现在按 {@code book_id} 隔离。
     *
     * <p><b>★ 问候（GREETING）也按账本过滤</b>（用户明确要求）：
     * 用户的意思是"当天已经看过一次问候了，换个账本不用再看第二遍"。
     * 问候落库时带的是<b>当天打开时选中的那个账本</b>，所以它天然只属于那一个账本 ——
     * 切到别的账本看不到它，正符合预期。
     *
     * @param userId 用户
     * @param bookId <b>可选</b>；为 null 时不按账本过滤（兼容旧行为 / 未选账本）
     * @param cursor 游标（上一页最后一条的 id）
     * @param size   每页条数
     */
    public ChatMessagePageVO pageMessages(Long userId, Long bookId, Long cursor, Integer size) {
        int pageSize = normalizeSize(size);

        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getUserId, userId)
                // ★ 指定账本才加条件；不传时保持"用户级"的旧行为
                .eq(bookId != null, ChatMessage::getBookId, bookId)
                // ★ cursor 为 null 时不加这个条件（取最新一页）
                .lt(cursor != null, ChatMessage::getId, cursor)
                .orderByDesc(ChatMessage::getId);

        // 多查一条用于判断"还有没有更多"；searchCount=false 避免多余的 COUNT
        Page<ChatMessage> page = new Page<>(1, pageSize + 1L, false);
        List<ChatMessage> rows = chatMessageMapper.selectPage(page, wrapper).getRecords();

        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            // 丢掉多查的那一条
            rows = new ArrayList<>(rows.subList(0, pageSize));
        }

        List<ChatMessageVO> items = new ArrayList<>(rows.size());
        for (ChatMessage row : rows) {
            items.add(toVO(row));
        }

        // 下一页游标 = 本页最后一条（最旧）的 id；没有更多时为 null
        Long nextCursor = hasMore && !items.isEmpty()
                ? items.get(items.size() - 1).id()
                : null;

        log.debug("拉取对话历史：userId={}, bookId={}, cursor={}, size={}, 返回={}, hasMore={}",
                userId, bookId, cursor, pageSize, items.size(), hasMore);

        return new ChatMessagePageVO(items, nextCursor, hasMore);
    }

    /**
     * 实体转 VO。
     *
     * <p><b>★ {@code meta_json} 的解析为什么仍然要 try/catch</b>：
     * <b>实测确认，非法 JSON 根本插不进 {@code t_chat_message}</b> ——
     * {@code meta_json} 是 MySQL 的 {@code json} 列，数据库在写入时就会拒绝
     * （抛 {@code DataIntegrityViolation}）。所以正常情况下 {@code readTree}
     * 不会失败。
     *
     * <p>但这里<b>仍然保留兜底</b>，因为穷尽"不会失败"要依赖两个外部前提：
     * <ol>
     *   <li>列类型一直是 {@code json}（若将来改成 {@code text} 就失去保护）</li>
     *   <li>所有写入方都经过数据库校验（若将来有批量导入绕过）</li>
     * </ol>
     * 一旦前提破裂，没有兜底就是"整个对话历史都打不开"。
     * <b>一条坏数据不该让用户看不到其它所有消息。</b>
     */
    private ChatMessageVO toVO(ChatMessage entity) {
        return new ChatMessageVO(
                entity.getId(),
                entity.getBookId(),
                entity.getRole(),
                entity.getType(),
                entity.getContent(),
                entity.getBillId(),
                parseMeta(entity.getId(), entity.getMetaJson()),
                entity.getTriggerType(),
                entity.getCreateTime());
    }

    /** 解析 meta_json；空串返回 null，非法 JSON 也返回 null（打 warn）。 */
    private JsonNode parseMeta(Long messageId, String metaJson) {
        if (metaJson == null || metaJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(metaJson);
        } catch (Exception e) {
            log.warn("消息 meta_json 解析失败，已置为 null：messageId={}, err={}",
                    messageId, e.getMessage());
            return null;
        }
    }

    /**
     * 修正每页条数。
     *
     * <p><b>★ 为什么是"修正"而不是"报错"</b>：
     * 分页大小是种"偏好"而不是"业务约束"。前端传个 0 或 500 属于笔误，
     * 直接夹到合法区间比抛 400 更友好（用户不会因为这个用不了对话）。
     * <b>但上限必须硬性夹住</b> —— 不夹的话一个请求就能拖垮内存。
     */
    private int normalizeSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
