package com.sakuya.notesatwill.controller;

import com.sakuya.notesatwill.common.result.Result;
import com.sakuya.notesatwill.entity.VO.ChatMessagePageVO;
import com.sakuya.notesatwill.entity.VO.GreetingVO;
import com.sakuya.notesatwill.entity.ChatMessage;
import com.sakuya.notesatwill.service.BillService;
import com.sakuya.notesatwill.service.ChatMessageService;
import com.sakuya.notesatwill.service.ChatService;
import com.sakuya.notesatwill.service.GreetingService;
import com.sakuya.notesatwill.service.SseEmitterManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 伙伴对话接口。
 *
 * <p><b>★ 读、写、推三条路径分得很清楚</b>：
 * <ul>
 *   <li>{@code GET /messages} —— 读历史（游标分页，{@link ChatService}）</li>
 *   <li>{@code POST /daily-open} —— 每日问候（<b>幂等</b>，{@link GreetingService}）</li>
 *   <li>{@code GET /stream} —— <b>SSE 推送通道</b>（{@link SseEmitterManager}）</li>
 * </ul>
 *
 * <p><b>★ 所有接口都需要登录</b>，消息按 {@code userId} 隔离，
 * 且不需要 {@code bookId} 参数 —— 对话是"用户级"的，不按账本切分
 * （消息里的 {@code book_id} 只是统计卡片当时的账本快照）。
 *
 * @author sakuya
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final GreetingService greetingService;
    private final SseEmitterManager sseEmitterManager;
    private final ChatMessageService chatMessageService;
    private final BillService billService;

    /**
     * 拉取对话历史（<b>游标分页，倒序</b>）。
     *
     * <pre>
     * 首次加载（最新 20 条）：
     *   GET /api/chat/messages?size=20
     * →
     * {
     *   "code": 200,
     *   "data": {
     *     "items": [
     *       { "id": 120, "role": "AI", "type": "TEXT", "content": "今天花了 125 元…",
     *         "billId": null, "meta": null, "triggerType": "DAILY_OPEN",
     *         "createTime": "2026-10-19 09:00:00" },
     *       { "id": 119, "role": "USER", "type": "TEXT", "content": "昨天吃饭25",
     *         "createTime": "2026-10-18 18:30:00" }
     *     ],
     *     "nextCursor": 101,
     *     "hasMore": true
     *   }
     * }
     *
     * 向下翻（更早的 20 条）：
     *   GET /api/chat/messages?cursor=101&size=20
     * </pre>
     *
     * <p><b>★★ 为什么是游标分页而不是 {@code page=2}</b>：
     * 对话是"持续追加"的场景。用 offset 分页时，只要来了新消息，
     * 用户正在看的第 2 页内容就会整体下移 —— <b>消息会重复出现</b>。
     * 按 id 定位则不受新消息影响，且翻到第几页都是同样的查询代价。
     *
     * <p><b>★ {@code items} 按 id 倒序</b>（最新的在最前），
     * 前端直接把数组渲染成消息流即可，不必自己排序。
     *
     * <p><b>★ {@code nextCursor} 为 null 表示"没有更早的消息了"</b>，
     * 前端据此停止上拉加载。
     *
     * @param userId 当前登录用户 id（来自 token）
     * @param cursor 游标：只取 id 小于它的消息；<b>首次加载不传</b>
     * @param size   每页条数，默认 20，上限 100（超出会被夹住而不是报错）
     */
    @GetMapping("/messages")
    public Result<ChatMessagePageVO> messages(@AuthenticationPrincipal Long userId,
                                              @RequestParam(required = false) Long bookId,
                                              @RequestParam(required = false) Long cursor,
                                              @RequestParam(required = false) Integer size) {
        return Result.success(chatService.pageMessages(userId, bookId, cursor, size));
    }

    /**
     * <b>SSE 推送通道</b>：前端用原生 {@code EventSource} 连上后，
     * 服务端所有"AI 主动说话"都通过它推下来。
     *
     * <pre>
     * const es = new EventSource('/api/chat/stream');
     * es.addEventListener('bill-created', e => appendCard(JSON.parse(e.data)));
     * es.addEventListener('greeting',     e => appendGreeting(JSON.parse(e.data)));
     * </pre>
     *
     * <p><b>★★ 为什么用 SSE 而不是 WebSocket</b>：
     * 本项目只需要服务端 → 客户端<b>单向</b>推（账单卡片、问候），
     * 没有双工需求。SSE 基于普通 HTTP、浏览器<b>自动重连</b>、
     * 无需协议升级与心跳设计，工作量远小于 WebSocket。
     *
     * <p><b>★ 连接超时 30 分钟</b>（见 {@code SseEmitterManager}）：
     * 不是"会话时长"，而是单条连接的存活上限。超时后浏览器会自动重连，用户无感。
     * <b>必须设超时</b> —— 不设的话断开后连接会永久挂在服务端。
     *
     * <p><b>★ 返回 {@code text/event-stream}</b>：这个 Content-Type 是 SSE 协议要求，
     * 缺了浏览器不会把它当事件流处理。
     *
     * <p><b>⚠ 注意</b>：浏览器关闭页面后，服务端要等到"写失败"或"超时"
     * 才会感知断开。这正是 `onCompletion/onTimeout/onError` 三个回调必须
     * 全部注册清理逻辑的原因。
     *
     * @param userId 当前登录用户 id（来自 token）
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal Long userId) {
        return sseEmitterManager.create(userId);
    }

    /**
     * <b>每日首次打开</b>：生成（或复用）今天的问候。
     *
     * <pre>
     * POST /api/chat/daily-open
     * →
     * { "code": 200,
     *   "data": { "greetingDate": "2026-10-19", "timeBucket": "MORNING",
     *             "content": "早上好！本月已花 1250 元…",
     *             "degraded": false, "alreadySent": false } }
     * </pre>
     *
     * <p><b>★★ 幂等：同一天连续调 10 次，只会有一条问候</b>
     * <pre>
     *   第 1 次 → 生成并落库，alreadySent = false（前端应播报）
     *   第 2~10 次 → 复用已有那条，alreadySent = true（前端只渲染，不重复播报）
     * </pre>
     * 唯一性由数据库唯一索引 {@code uk_user_date} 保证，
     * <b>不是"先查再插"</b>（并发下必漏）。
     *
     * <p><b>★ {@code degraded=true} 表示 AI 不可用、用了模板兜底</b>：
     * 内容照常展示（模板里也有真实数字），前端可选择性提示"AI 暂时不可用"。
     *
     * <p><b>★ 为什么用 POST 而不是 GET</b>：它会<b>写数据库</b>（生成问候）。
     * 虽然语义上是"打开页面"，但把它做成 GET 会让浏览器预取 / 爬虫
     * 意外触发写入。POST 更诚实。
     *
     * @param userId 当前登录用户 id
     */
    @PostMapping("/daily-open")
    public Result<GreetingVO> dailyOpen(@AuthenticationPrincipal Long userId) {
        return Result.success(greetingService.dailyOpen(userId));
    }

    /**
     * <b>删除一条对话消息</b>；若是账单卡片且要求级联，则连带删除对应账单。
     *
     * <pre>
     * 只删消息（安全，默认）：
     *   DELETE /api/chat/messages/120
     *   → 对话流里少一张卡，账单仍然存在
     *
     * 连账单一起删（危险，需前端二次确认）：
     *   DELETE /api/chat/messages/120?cascadeBill=true
     *   → 账单被删除，账户余额同步回滚
     * </pre>
     *
     * <p><b>★★ 为什么"连带删账单"必须是显式参数，绝不能是默认行为</b>
     * <pre>
     *   删除聊天记录的常规预期 = "清掉这条消息"
     *   但对 BILL_CARD 来说，连带删账单会
     *     · 真的删掉一笔账
     *     · 回滚账户余额
     *   这是完全不同量级的破坏性操作 ——
     *   绝不能在用户只是"想清清聊天"时静默发生。
     * </pre>
     * 所以默认 {@code cascadeBill=false}，前端在弹二次确认框、明确告知
     * "账单和余额也会被撤销"之后，才允许传 {@code true}。
     *
     * <p><b>★ 为什么编排放在 Controller 而不是某个 Service</b>
     * <pre>
     *   BillService        → ChatMessageService（写账单卡片，单向）
     *   ChatMessageService → BillService（删账单）      ← 若加这条就成环
     * </pre>
     * 加第二条边会让 Spring 启动直接失败（实测确认）。
     * 用 {@code @Lazy} 能骗过容器，但那只是把双向依赖藏起来。
     * <b>正确做法是让依赖保持单向，把"协调两个 Service"的职责放在 Controller
     * —— 那里本来就是编排层。</b>
     *
     * <p><b>★ 删除顺序：先删账单，再删消息</b>。
     * 若先删消息、删账单时失败，消息已经没了但账还在，
     * 用户会以为"清理了聊天"而账单其实还在。反过来（先删账单）失败时，
     * 消息还在，用户能重试，状态是自洽的。
     *
     * <p><b>⚠ 事务边界</b>：这里两个 Service 各自带 {@code @Transactional}，
     * 属于两个独立事务。若删账单成功、删消息失败，会出现"账单没了但卡片还在"
     * —— 卡片点开是 404。这个窗口极窄，且可通过"卡片渲染时发现 billId 已失效
     * 就显示为已删除"兜住，不影响金额与余额正确性。
     *
     * @param userId      当前登录用户 id
     * @param id          消息 id
     * @param cascadeBill 是否连带删除关联账单（<b>破坏性</b>，默认 false）
     */
    @DeleteMapping("/messages/{id}")
    public Result<DeletionVO> deleteMessage(@AuthenticationPrincipal Long userId,
                                            @PathVariable Long id,
                                            @RequestParam(required = false,
                                                    defaultValue = "false") boolean cascadeBill) {
        // ① 先读消息（同时也是归属校验：不是我的 → 6000）
        ChatMessage msg = chatMessageService.peek(userId, id);

        boolean isBillCard = ChatMessageService.TYPE_BILL_CARD.equals(msg.getType())
                && msg.getBillId() != null;

        // ② 级联删账单（要求级联、且这条确实是账单卡片）
        if (cascadeBill && isBillCard) {
            // ★ 调 BillService.deleteBill 而不是自己删 t_bill：
            //   它会回滚资金流水与余额、清理统计缓存，
            //   并【顺带】删掉这张卡片（双向级联的方向一）。
            //   绕过它直接删表会让余额永久错掉，对账接口立刻报不平。
            billService.deleteBill(userId, msg.getBillId());

            // ★★ deleteBill 内部已经把这卡片逻辑删除了，
            //    所以这里【不能再调】deleteMessageReturning —— 那会抛"消息不存在"。
            //    两个级联方向在这里交汇：删账单 → 卡片消失，已包含我们要的效果。
            return Result.success(new DeletionVO(id, msg.getBillId(), true, true));
        }

        // ③ 只删消息（安全默认：账单保留）
        chatMessageService.deleteMessageReturning(userId, id);
        return Result.success(new DeletionVO(id, null, isBillCard, false));
    }

    /**
     * 删除结果。
     *
     * @param messageId      被删除的消息 id
     * @param billId         连带删除的账单 id；未级联时为 null
     * @param wasBillCard    这条消息原本是不是账单卡片
     * @param cascadeApplied 本次是否真的执行了级联（要求级联但消息无关联账单时为 false）
     */
    public record DeletionVO(Long messageId, Long billId,
                             boolean wasBillCard, boolean cascadeApplied) {
    }
}
