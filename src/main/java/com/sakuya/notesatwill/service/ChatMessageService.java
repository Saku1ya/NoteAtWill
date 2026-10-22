package com.sakuya.notesatwill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.common.util.AfterCommit;
import com.sakuya.notesatwill.entity.Bill;
import com.sakuya.notesatwill.entity.ChatMessage;
import com.sakuya.notesatwill.entity.DailyGreeting;
import com.sakuya.notesatwill.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 对话消息的<b>写入</b>侧（D9）。
 *
 * <p><b>★ 为什么和 {@link ChatService} 分开</b>：
 * {@code ChatService} 只负责"读"（游标分页），本类只负责"写"。
 * 读路径没有事务、写路径需要事务，职责与事务边界都不同 ——
 * 合在一个类里会让"这个方法到底加不加 {@code @Transactional}"变得难以判断。
 *
 * <p><b>★★ 写入的三种来源</b>（{@code trigger_type}）：
 * <table border="1">
 *   <caption>消息来源</caption>
 *   <tr><th>trigger_type</th><th>谁触发</th><th>type</th></tr>
 *   <tr><td>{@code MANUAL_BILL}</td><td>手动记账成功</td><td>BILL_CARD</td></tr>
 *   <tr><td>{@code AI_PARSE}</td><td>AI 记账确认</td><td>BILL_CARD</td></tr>
 *   <tr><td>{@code DAILY_OPEN}</td><td>每日首次打开</td><td>GREETING</td></tr>
 * </table>
 *
 * <p><b>★ 字段名是 {@code trigger_type} 而不是 {@code trigger}</b>
 * —— {@code trigger} 是 MySQL 保留字。
 *
 * @author sakuya
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageService {

    /** 发送方：AI 伙伴 */
    public static final String ROLE_AI = "AI";
    /** 发送方：用户 */
    public static final String ROLE_USER = "USER";

    /** 消息类型：纯文本 */
    public static final String TYPE_TEXT = "TEXT";
    /** 消息类型：账单卡片（{@code bill_id} 指向账单） */
    public static final String TYPE_BILL_CARD = "BILL_CARD";
    /** 消息类型：每日问候 */
    public static final String TYPE_GREETING = "GREETING";

    /** 触发来源：手动记账 */
    public static final String TRIGGER_MANUAL_BILL = "MANUAL_BILL";
    /** 触发来源：AI 解析后确认 */
    public static final String TRIGGER_AI_PARSE = "AI_PARSE";
    /** 触发来源：每日打开 */
    public static final String TRIGGER_DAILY_OPEN = "DAILY_OPEN";

    /** SSE 事件名：账单卡片已创建 */
    public static final String EVENT_BILL_CREATED = "bill-created";

    private final ChatMessageMapper chatMessageMapper;
    private final SseEmitterManager sseEmitterManager;

    // ========================================================================
    // 账单卡片（7.4）
    // ========================================================================

    /**
     * 记账成功后写一张<b>账单卡片</b>消息，并在<b>事务提交后</b>通过 SSE 推给该用户。
     *
     * <p><b>★★ 两个必须遵守的约定</b>
     * <ol>
     *   <li><b>必须在记账的同一事务里调用</b>：卡片与账单要么都成功、要么都失败。
     *       否则会出现"账记上了但对话流里没有卡片"或反之。</li>
     *   <li><b>推送必须在提交之后</b>：由 {@link AfterCommit} 保证。
     *       若在事务内推送，用户会看到卡片出现、然后提交失败、刷新后卡片消失 ——
     *       <b>用户已经被误导了</b>。</li>
     * </ol>
     *
     * <p><b>★ 卡片里存什么</b>：{@code bill_id} 指向账单，<b>不存金额快照</b>
     * （与表注释一致）。这样账单被修改后，卡片显示的是最新值，
     * 不会出现"卡片显示 25 元、账单实际 30 元"。
     *
     * @param userId     用户 id
     * @param bill       已落库的账单（需要 id / bookId / amount / type）
     * @param aiParse    true = 来自 AI 确认，false = 手动记账（只影响 trigger_type）
     */
    @Transactional
    public void appendBillCard(Long userId, Bill bill, boolean aiParse) {
        if (bill == null || bill.getId() == null) {
            return;
        }

        try {
            ChatMessage msg = new ChatMessage();
            msg.setUserId(userId);
            msg.setBookId(bill.getBookId());
            msg.setRole(ROLE_AI);
            msg.setType(TYPE_BILL_CARD);
            // ★ content 给一句可读的摘要，前端即使不渲染卡片也能显示文字
            msg.setContent(describeBill(bill));
            msg.setBillId(bill.getId());
            msg.setTriggerType(aiParse ? TRIGGER_AI_PARSE : TRIGGER_MANUAL_BILL);
            chatMessageMapper.insert(msg);

            // ★★ 提交后再推送：防止"推送成功但事务回滚"误导用户
            AfterCommit.run(
                    () -> sseEmitterManager.send(userId, EVENT_BILL_CREATED,
                            cardPayload(bill, msg.getId())),
                    "推送账单卡片 userId=" + userId + ", billId=" + bill.getId());

            log.debug("账单卡片已写入：userId={}, billId={}, messageId={}",
                    userId, bill.getId(), msg.getId());

        } catch (Exception e) {
            // ★ 关键决策：卡片写失败**不能**让记账失败
            //   用户的核心诉求是"账记上了"，卡片是附带的可视化。
            //   如果这里抛出去，整个事务回滚 → 用户记不了账，这是本末倒置。
            log.warn("写账单卡片失败（不影响记账）：userId={}, billId={}, err={}",
                    userId, bill == null ? null : bill.getId(), e.getMessage());
        }
    }

    /** 卡片推送给前端的载荷（前端直接渲染成一张卡）。 */
    private Map<String, Object> cardPayload(Bill bill, Long messageId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("billId", bill.getId());
        payload.put("bookId", bill.getBookId());
        payload.put("amount", bill.getAmount());
        payload.put("type", bill.getType());
        payload.put("categoryId", bill.getCategoryId());
        payload.put("billTime", bill.getBillTime());
        payload.put("remark", bill.getRemark());
        payload.put("content", describeBill(bill));
        return payload;
    }

    /** 一句话摘要，用于 {@code content} 字段与无卡片渲染时的降级展示。 */
    private String describeBill(Bill bill) {
        String direction = "EXPENSE".equals(bill.getType()) ? "支出" : "收入";
        BigDecimal amount = bill.getAmount() == null ? BigDecimal.ZERO : bill.getAmount();
        String money = amount.stripTrailingZeros().toPlainString();
        String remark = bill.getRemark();
        return remark == null || remark.isBlank()
                ? String.format("已记录一笔%s %s 元", direction, money)
                : String.format("已记录：%s %s 元", remark, money);
    }

    // ========================================================================
    // 问候消息（7.5）
    // ========================================================================

    /**
     * 把一条问候写进对话流。
     *
     * <p><b>★ 为什么问候也要写一条消息</b>：
     * 对话流是用户看 AI 的主界面。如果问候只存在 {@code t_daily_greeting} 里，
     * 用户往上翻历史就看不到"昨天早上 AI 说过什么"。写一条消息后历史是连续的。
     *
     * <p><b>★ 本方法吞掉异常</b>：问候已经落库并要返回给前端了，
     * 聊天消息只是"让历史更完整"的附加数据。写失败最多历史里少一条，
     * <b>不该让首屏接口报错</b>。
     *
     * @param userId   用户 id
     * @param bookId   账本 id（记录当时的账本口径）
     * @param greeting 已落库的问候
     */
    @Transactional
    public void appendGreeting(Long userId, Long bookId, DailyGreeting greeting) {
        if (greeting == null) {
            return;
        }
        try {
            ChatMessage msg = new ChatMessage();
            msg.setUserId(userId);
            msg.setBookId(bookId);
            msg.setRole(ROLE_AI);
            msg.setType(TYPE_GREETING);
            msg.setContent(greeting.getContent());
            msg.setTriggerType(TRIGGER_DAILY_OPEN);
            chatMessageMapper.insert(msg);
            log.debug("问候已写入对话流：userId={}, messageId={}", userId, msg.getId());
        } catch (Exception e) {
            log.warn("问候写入对话流失败（忽略）：userId={}, err={}", userId, e.getMessage());
        }
    }

    // ========================================================================
    // 双向级联删除（7.7）
    // ========================================================================

    /**
     * <b>删除某账单对应的卡片消息</b>（"删账单 → 卡片消失"方向）。
     *
     * <p><b>★ 必须在删除账单的同一事务里调用</b>：账单删了但卡片还在，
     * 对话流里就会出现一张点进去 404 的死卡片。
     *
     * <p><b>★ 为什么用 {@code delete(wrapper)} 而不是逐个 {@code deleteById}</b>：
     * 一条账单理论上只有一张卡片，但历史数据/异常重试可能留下多条。
     * 用条件删除一次清干净，避免"删了一条还剩一条"。
     *
     * @param userId 用户 id（★ 必须带，防止删到别人的消息）
     * @param billId 账单 id
     * @return 实际删除的消息数
     */
    @Transactional
    public int removeByBillId(Long userId, Long billId) {
        if (userId == null || billId == null) {
            return 0;
        }
        try {
            int deleted = chatMessageMapper.delete(
                    new LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getUserId, userId)
                            .eq(ChatMessage::getBillId, billId));
            if (deleted > 0) {
                log.info("已删除账单对应的卡片消息：userId={}, billId={}, 条数={}",
                        userId, billId, deleted);
            }
            return deleted;
        } catch (Exception e) {
            // ★ 卡片删除失败不该阻止账单删除 —— 账单是主数据，卡片是展示层。
            //   最坏结果是对话流里留一张死卡片（点开 404），
            //   也比"账单删不掉、用户卡住"好。
            log.warn("删除账单卡片失败（不影响账单删除）：userId={}, billId={}, err={}",
                    userId, billId, e.getMessage());
            return 0;
        }
    }

    /**
     * <b>只读一条消息</b>（含归属校验），用于删除前的判断。
     *
     * <p><b>★ 为什么要单独一个 peek 而不是让调用方自己查 Mapper</b>：
     * 归属校验（"不存在"与"不是我的"都抛 6000）必须在所有入口保持一致。
     * 若让调用方自己 {@code selectById} 再判断，每个调用点都要重写一遍 ——
     * 迟早有一处写成"只判 null 不判 userId"，那就是越权。
     *
     * @param userId    用户 id
     * @param messageId 消息 id
     * @return 消息实体
     * @throws BusinessException 消息不存在/不属于我（6000）
     */
    public ChatMessage peek(Long userId, Long messageId) {
        ChatMessage msg = chatMessageMapper.selectById(messageId);
        if (msg == null || !Objects.equals(msg.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_FOUND);
        }
        return msg;
    }

    /**
     * <b>删除消息本身（不碰账单）</b>。
     *
     * <p><b>★★ 为什么本方法【不】做"连带删账单"，尽管那是需求</b>
     * <pre>
     *   BillService 需要 ChatMessageService 来"写账单卡片"
     *   ChatMessageService 若再依赖 BillService 来"删账单"
     *        ↓
     *   循环依赖 → Spring 启动直接失败（已实测确认）
     * </pre>
     * 用 {@code @Lazy} 能骗过容器，但那只是把问题藏起来：
     * 两个本应单向依赖的领域服务被绑成了双向。
     *
     * <p><b>正确的解法是让依赖保持单向</b>：
     * <ul>
     *   <li>{@code BillService} → {@code ChatMessageService}（写卡片，单向）</li>
     *   <li>删卡片连带删账单的<b>编排放在 Controller 层</b> ——
     *       那里本来就该是"协调多个 Service"的地方</li>
     * </ul>
     * 所以本方法只删消息，返回删之前读到的消息信息，
     * 由调用方决定要不要再去删账单。
     *
     * @param userId    用户 id
     * @param messageId 消息 id
     * @return 被删消息的快照（调用方据此判断是否需要级联删账单）
     * @throws BusinessException 消息不存在/不属于我（6000）
     */
    @Transactional
    public ChatMessage deleteMessageReturning(Long userId, Long messageId) {
        ChatMessage msg = chatMessageMapper.selectById(messageId);
        if (msg == null || !Objects.equals(msg.getUserId(), userId)) {
            // "不存在"与"不是我的"合并成同一个码，避免被用来枚举别人的消息
            throw new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_FOUND);
        }
        chatMessageMapper.deleteById(messageId);
        log.info("已删除消息：userId={}, messageId={}, type={}",
                userId, messageId, msg.getType());
        return msg;
    }
}
