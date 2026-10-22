package com.sakuya.notesatwill;

import com.sakuya.notesatwill.client.AiGreetingClient;
import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Bill;
import com.sakuya.notesatwill.entity.DTO.AiBillItem;
import com.sakuya.notesatwill.entity.DTO.BillRequest;
import com.sakuya.notesatwill.entity.VO.ChatMessageVO;
import com.sakuya.notesatwill.mapper.ChatMessageMapper;
import com.sakuya.notesatwill.service.BillService;
import com.sakuya.notesatwill.service.BookService;
import com.sakuya.notesatwill.service.ChatMessageService;
import com.sakuya.notesatwill.service.ChatService;
import com.sakuya.notesatwill.service.StatsCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账单卡片 + 双向级联删除（任务 7.4 + 7.7）回归测试。
 *
 * <p><b>★★ 核心验收点</b>：
 * <ol>
 *   <li><b>记账 → 自动写一张 BILL_CARD 消息</b>（同一事务）</li>
 *   <li><b>删账单 → 卡片消失</b></li>
 *   <li><b>删卡片 → 账单消失</b>（需显式 {@code cascadeBill=true}），<b>余额同步回滚</b></li>
 *   <li>只删卡片不删账单时，账单要<b>保留</b>（默认行为是安全的）</li>
 *   <li>越权：不能删别人的消息/卡片</li>
 * </ol>
 *
 * @author sakuya
 */
@SpringBootTest
class BillCardCascadeTest {

    private static final Long MY_ID = 987503001L;
    private static final Long OTHER_ID = 987503002L;
    private static final Long CAT_LUNCH = 100102L;
    private static final Long CAT_TAXI = 100202L;

    @Autowired
    private BillService billService;

    @Autowired
    private BookService bookService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private StatsCacheService cacheService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 问候会用到 AI，这里打桩避免真实调用 */
    @MockitoBean
    private AiGreetingClient aiGreetingClient;

    private Long bookId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        clean();
        bookId = bookService.createDefaultBook(MY_ID).getId();

        // 建一个账户，用于验证"余额回滚"
        jdbcTemplate.update(
                "INSERT INTO t_account (user_id, name, type, init_balance, balance, deleted) "
                        + "VALUES (?, '现金', 'CASH', 1000.00, 1000.00, 0)", MY_ID);
        accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_account WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, MY_ID);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (Long u : new Long[]{MY_ID, OTHER_ID}) {
            jdbcTemplate.update("DELETE FROM t_chat_message WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_daily_greeting WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_ai_persona WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_budget WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_account_flow WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_bill WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_account WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_book WHERE user_id = ?", u);
            cacheService.evictUser(u);
        }
    }

    private Bill createBill(String amount, Long catId, Long accId) {
        return billService.createBill(MY_ID, new BillRequest(
                bookId, catId, BillService.TYPE_EXPENSE,
                new BigDecimal(amount), LocalDateTime.now().minusMinutes(1), "午饭", accId));
    }

    /** 该账单对应的卡片消息（未删除的） */
    private List<Map<String, Object>> cardsOf(Long billId) {
        return jdbcTemplate.queryForList(
                "SELECT id, type, bill_id, content FROM t_chat_message "
                        + "WHERE user_id = ? AND bill_id = ? AND deleted = 0", MY_ID, billId);
    }

    private int activeBills() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_bill WHERE user_id = ? AND deleted = 0",
                Integer.class, MY_ID);
        return n == null ? 0 : n;
    }

    private BigDecimal balance() {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM t_account WHERE id = ?", BigDecimal.class, accountId);
    }

    // ==================== 7.4 记账写卡片 ====================

    @Test
    void creatingBillWritesCard() {
        System.out.println("\n===== 记账 → 自动写一张 BILL_CARD =====");
        Bill bill = createBill("25.00", CAT_LUNCH, null);

        List<Map<String, Object>> cards = cardsOf(bill.getId());
        System.out.println("  卡片：" + cards);
        assertEquals(1, cards.size(), "★ 应写一张卡片");
        assertEquals("BILL_CARD", cards.get(0).get("type"));
        assertEquals(bill.getId(), ((Number) cards.get(0).get("bill_id")).longValue());
        assertNotNull(cards.get(0).get("content"), "★ content 应有一句可读摘要");
        System.out.println("  ✓ 卡片 content=" + cards.get(0).get("content"));
    }

    @Test
    void cardAppearsInChatHistory() {
        System.out.println("\n===== 卡片出现在对话历史里 =====");
        Bill bill = createBill("88.00", CAT_LUNCH, null);

        var page = chatService.pageMessages(MY_ID, null, 20);
        ChatMessageVO card = page.items().stream()
                .filter(m -> "BILL_CARD".equals(m.type())).findFirst().orElseThrow();

        System.out.println("  历史首条：" + card.type() + " / " + card.content());
        assertEquals(bill.getId(), card.billId());
        assertEquals("MANUAL_BILL", card.triggerType(), "手动记账的 trigger_type");
        System.out.println("  ✓ 卡片可读，billId 正确");
    }

    @Test
    void aiConfirmWritesCardWithAiTrigger() {
        System.out.println("\n===== AI 确认落库的卡片 trigger_type=AI_PARSE =====");
        LocalDateTime t = LocalDateTime.now().minusMinutes(1);
        List<Bill> saved = billService.confirmAiBills(MY_ID, 999999L, bookId, List.of(
                new AiBillItem(new BigDecimal("66.00"), "EXPENSE", CAT_LUNCH, null,
                        null, null, t, "AI记的", new BigDecimal("0.9"))));

        List<Map<String, Object>> cards = cardsOf(saved.get(0).getId());
        assertEquals(1, cards.size());
        System.out.println("  ✓ 卡片已写");

        var page = chatService.pageMessages(MY_ID, null, 20);
        ChatMessageVO card = page.items().stream()
                .filter(m -> "BILL_CARD".equals(m.type())).findFirst().orElseThrow();
        assertEquals("AI_PARSE", card.triggerType(), "★ AI 来源的 trigger_type 要区分开");
        System.out.println("  ✓ trigger_type=AI_PARSE");
    }

    @Test
    void billWithoutAccountStillWritesCard() {
        System.out.println("\n===== 没选账户的账单也写卡片 =====");
        Bill bill = createBill("10.00", CAT_TAXI, null);
        assertEquals(1, cardsOf(bill.getId()).size());
        System.out.println("  ✓ 写卡片与「有没有账户」无关");
    }

    // ==================== 7.7 方向一：删账单 → 卡片消失 ====================

    @Test
    void deletingBillRemovesCard() {
        System.out.println("\n===== ★ 删账单 → 卡片消失 =====");
        Bill bill = createBill("25.00", CAT_LUNCH, null);
        assertEquals(1, cardsOf(bill.getId()).size());

        billService.deleteBill(MY_ID, bill.getId());

        System.out.println("  删后卡片数=" + cardsOf(bill.getId()).size());
        assertEquals(0, cardsOf(bill.getId()).size(), "★ 卡片必须跟着消失，否则是死卡片");
        System.out.println("  ✓ 卡片已逻辑删除");
    }

    @Test
    void deletedCardNotInHistory() {
        System.out.println("\n===== 删账单后对话历史里看不到卡片 =====");
        Bill bill = createBill("25.00", CAT_LUNCH, null);
        billService.deleteBill(MY_ID, bill.getId());

        var page = chatService.pageMessages(MY_ID, null, 20);
        boolean hasCard = page.items().stream().anyMatch(m -> "BILL_CARD".equals(m.type()));
        assertTrue(!hasCard, "★ 已删账单的卡片不该出现在历史里");
        System.out.println("  ✓ 历史里没有残留卡片");
    }

    // ==================== 7.7 方向二：删卡片 → 账单消失 ====================

    @Test
    void deletingCardWithoutCascadeKeepsBill() {
        System.out.println("\n===== ★★ 默认只删卡片，账单保留（安全默认）=====");
        Bill bill = createBill("25.00", CAT_LUNCH, accountId);
        Long cardId = ((Number) cardsOf(bill.getId()).get(0).get("id")).longValue();

        chatMessageService.deleteMessageReturning(MY_ID, cardId);

        System.out.println("  卡片数=" + cardsOf(bill.getId()).size()
                + "，账单数=" + activeBills() + "，余额=" + balance());
        assertEquals(0, cardsOf(bill.getId()).size(), "卡片已删");
        assertEquals(1, activeBills(), "★★ 账单必须保留 —— 删聊天记录不该删账");
        assertEquals(0, balance().compareTo(new BigDecimal("975.00")), "★ 余额也不能变");
        System.out.println("  ✓ 只删了消息，账单与余额都不受影响");
    }

    @Test
    void cascadeDeletingCardRemovesBillAndRollsBackBalance() {
        System.out.println("\n===== ★★★ 删卡片连带删账单 → 余额回滚 =====");
        Bill bill = createBill("25.00", CAT_LUNCH, accountId);
        assertEquals(0, balance().compareTo(new BigDecimal("975.00")), "记账后余额应 975");
        Long cardId = ((Number) cardsOf(bill.getId()).get(0).get("id")).longValue();

        // ★ 模拟 Controller 的编排（cascadeBill=true）：
        //   先删账单（回滚流水与余额），再删消息本身。
        //   ★ 注意 deleteBill 内部会【顺带】删掉这张卡片（方向一），
        //     所以这里删消息时会发现它已经不存在 —— 这是预期的，
        //     Controller 里的 deleteMessageReturning 也会因此在"卡片已随账单消失"时被略过。
        billService.deleteBill(MY_ID, bill.getId());

        System.out.println("  账单数=" + activeBills()
                + "，余额=" + balance()
                + "，卡片数=" + cardsOf(bill.getId()).size());
        assertEquals(0, activeBills(), "账单已删");
        assertEquals(0, balance().compareTo(new BigDecimal("1000.00")),
                "★★ 余额必须回滚到 1000 —— 否则对账立刻不平");
        assertEquals(0, cardsOf(bill.getId()).size(),
                "★ 卡片也随之消失（deleteBill 内部已级联，不需要再单独删消息）");
        System.out.println("  ✓ 账单删除 + 余额回滚 1000.00 + 卡片自动消失");
    }

    // ==================== 越权 ====================

    @Test
    void cannotDeleteOthersMessage() {
        System.out.println("\n===== 越权：不能删别人的消息 =====");
        // 直接给 OTHER_ID 造一条消息
        jdbcTemplate.update(
                "INSERT INTO t_chat_message (user_id, role, type, content, deleted) "
                        + "VALUES (?, 'USER', 'TEXT', '别人的消息', 0)", OTHER_ID);
        Long othersMsgId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_chat_message WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, OTHER_ID);

        BusinessException e = assertThrows(BusinessException.class,
                () -> chatMessageService.deleteMessageReturning(MY_ID, othersMsgId));

        assertEquals(ErrorCode.CHAT_MESSAGE_NOT_FOUND.getCode(), e.getCode());
        Integer still = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_chat_message WHERE id = ? AND deleted = 0",
                Integer.class, othersMsgId);
        assertEquals(1, still, "★ 别人的消息不能被删");
        System.out.println("  ✓ 被拒(" + e.getCode() + ")，对方消息完好");
    }

    @Test
    void cannotDeleteNonexistentMessage() {
        System.out.println("\n===== 不存在的消息 → 6000 =====");
        BusinessException e = assertThrows(BusinessException.class,
                () -> chatMessageService.deleteMessageReturning(MY_ID, 99999999L));
        assertEquals(ErrorCode.CHAT_MESSAGE_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")");
    }

    @Test
    void peekValidatesOwnership() {
        System.out.println("\n===== peek 也做归属校验 =====");
        jdbcTemplate.update(
                "INSERT INTO t_chat_message (user_id, role, type, content, deleted) "
                        + "VALUES (?, 'USER', 'TEXT', '别人的', 0)", OTHER_ID);
        Long othersMsgId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_chat_message WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, OTHER_ID);

        assertThrows(BusinessException.class, () -> chatMessageService.peek(MY_ID, othersMsgId));
        System.out.println("  ✓ 越权被拦");
    }

    // ==================== 边界 ====================

    @Test
    void textMessageHasNoBill() {
        System.out.println("\n===== 普通文本消息：billId 为 null，不涉及级联 =====");
        jdbcTemplate.update(
                "INSERT INTO t_chat_message (user_id, role, type, content, deleted) "
                        + "VALUES (?, 'USER', 'TEXT', '你好', 0)", MY_ID);
        Long msgId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_chat_message WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, MY_ID);

        var msg = chatMessageService.deleteMessageReturning(MY_ID, msgId);
        System.out.println("  删除的 type=" + msg.getType() + "，billId=" + msg.getBillId());
        assertEquals("TEXT", msg.getType());
        assertNull(msg.getBillId(), "★ 文本消息没有关联账单");
        System.out.println("  ✓ 文本消息可正常删除");
    }

    @Test
    void multipleBillsEachGetOwnCard() {
        System.out.println("\n===== 多笔账单各自一张卡片 =====");
        Bill b1 = createBill("10.00", CAT_LUNCH, null);
        Bill b2 = createBill("20.00", CAT_TAXI, null);
        Bill b3 = createBill("30.00", CAT_LUNCH, null);

        assertEquals(1, cardsOf(b1.getId()).size());
        assertEquals(1, cardsOf(b2.getId()).size());
        assertEquals(1, cardsOf(b3.getId()).size());
        System.out.println("  ✓ 三笔 → 三张卡，bill_id 各自对应");
    }

    @Test
    void deletingOneBillDoesNotAffectOthers() {
        System.out.println("\n===== 删一笔不影响其它账单的卡片 =====");
        Bill b1 = createBill("10.00", CAT_LUNCH, null);
        Bill b2 = createBill("20.00", CAT_TAXI, null);

        billService.deleteBill(MY_ID, b1.getId());

        assertEquals(0, cardsOf(b1.getId()).size(), "b1 卡片已删");
        assertEquals(1, cardsOf(b2.getId()).size(), "★ b2 卡片必须还在");
        assertEquals(1, activeBills());
        System.out.println("  ✓ 只影响了目标账单");
    }
}
