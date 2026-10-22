package com.sakuya.notesatwill;

import com.sakuya.notesatwill.entity.ChatMessage;
import com.sakuya.notesatwill.entity.VO.ChatMessagePageVO;
import com.sakuya.notesatwill.entity.VO.ChatMessageVO;
import com.sakuya.notesatwill.mapper.ChatMessageMapper;
import com.sakuya.notesatwill.service.ChatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话消息游标分页（任务 7.2）回归测试。
 *
 * <p><b>★★ 核心验收点：新消息不能挤动已经翻过的位置</b>
 * 这正是"必须用游标分页而不是 offset 分页"的原因。
 * 测试里专门模拟"用户看完第 1 页 → 来了新消息 → 翻第 2 页"，
 * 断言第 2 页与第 1 页<b>没有重复、没有遗漏</b>。
 *
 * <p>其它验收点：倒序、hasMore、nextCursor、size 夹取、用户隔离、逻辑删除过滤。
 *
 * @author sakuya
 */
@SpringBootTest
class ChatMessageQueryTest {

    private static final Long MY_ID = 987499001L;
    private static final Long OTHER_ID = 987499002L;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long bookId;

    @BeforeEach
    void setUp() {
        clean();
        // 直接插一条账本（不经过 BookService，本测试只关心消息）
        jdbcTemplate.update(
                "INSERT INTO t_book (user_id, name, is_default, sort, deleted, create_time, update_time) "
                        + "VALUES (?, '测试账本', 1, 0, 0, NOW(), NOW())", MY_ID);
        bookId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_book WHERE user_id = ? ORDER BY id DESC LIMIT 1", Long.class, MY_ID);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (Long u : new Long[]{MY_ID, OTHER_ID}) {
            jdbcTemplate.update("DELETE FROM t_chat_message WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_book WHERE user_id = ?", u);
        }
    }

    /** 造一条消息；返回其 id。 */
    private Long newMessage(Long userId, String role, String type, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setUserId(userId);
        msg.setBookId(bookId);
        msg.setRole(role);
        msg.setType(type);
        msg.setContent(content);
        msg.setTriggerType("MANUAL_BILL");
        chatMessageMapper.insert(msg);
        return msg.getId();
    }

    private List<Long> idsOf(ChatMessagePageVO page) {
        return page.items().stream().map(ChatMessageVO::id).collect(Collectors.toList());
    }

    // ==================== 基础查询 ====================

    @Test
    void returnsNewestFirst() {
        System.out.println("\n===== 返回顺序：最新在前（id 倒序）=====");
        Long m1 = newMessage(MY_ID, "USER", "TEXT", "第一条");
        Long m2 = newMessage(MY_ID, "AI", "TEXT", "第二条");
        Long m3 = newMessage(MY_ID, "USER", "TEXT", "第三条");

        ChatMessagePageVO page = chatService.pageMessages(MY_ID, null, 20);

        System.out.println("  插入顺序: " + m1 + " → " + m2 + " → " + m3);
        System.out.println("  返回顺序: " + idsOf(page));
        assertEquals(List.of(m3, m2, m1), idsOf(page), "★ 最新（id 最大）必须在最前");
        System.out.println("  ✓ 倒序正确");
    }

    @Test
    void returnsOnlyOwnMessages() {
        System.out.println("\n===== 用户隔离：看不到别人的消息 =====");
        Long mine = newMessage(MY_ID, "USER", "TEXT", "我的");
        Long others = newMessage(OTHER_ID, "USER", "TEXT", "别人的");

        ChatMessagePageVO page = chatService.pageMessages(MY_ID, null, 20);

        System.out.println("  我的 id=" + mine + "，别人的 id=" + others
                + "，返回 " + idsOf(page));
        assertEquals(1, page.items().size());
        assertEquals(mine, page.items().get(0).id());
        assertFalse(idsOf(page).contains(others));
        System.out.println("  ✓ 只返回自己的");
    }

    @Test
    void excludesLogicallyDeletedMessages() {
        System.out.println("\n===== 逻辑删除的消息不出现 =====");
        Long keep = newMessage(MY_ID, "USER", "TEXT", "保留");
        Long removed = newMessage(MY_ID, "USER", "TEXT", "要删的");

        chatMessageMapper.deleteById(removed);

        ChatMessagePageVO page = chatService.pageMessages(MY_ID, null, 20);

        System.out.println("  返回 " + idsOf(page) + "（已删 id=" + removed + "）");
        assertEquals(List.of(keep), idsOf(page), "★ 已逻辑删除的消息不能被查出来");
        System.out.println("  ✓ 过滤生效（@TableLogic 自动加 deleted = 0）");
    }

    @Test
    void emptyHistoryReturnsEmptyPage() {
        System.out.println("\n===== 没有历史消息 → 空页 =====");
        ChatMessagePageVO page = chatService.pageMessages(MY_ID, null, 20);

        assertEquals(0, page.items().size());
        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
        System.out.println("  ✓ 空列表、hasMore=false、nextCursor=null");
    }

    // ==================== ★★ 游标分页正确性 ====================

    @Test
    void cursorPaginationWalksAllMessagesExactlyOnce() {
        System.out.println("\n===== ★★ 逐页翻完所有消息：不重复、不遗漏 =====");
        // 造 25 条
        for (int i = 1; i <= 25; i++) {
            newMessage(MY_ID, "USER", "TEXT", "消息" + i);
        }

        List<Long> collected = new java.util.ArrayList<>();
        Long cursor = null;
        int pages = 0;
        while (true) {
            ChatMessagePageVO page = chatService.pageMessages(MY_ID, cursor, 10);
            pages++;
            collected.addAll(idsOf(page));
            System.out.println("  第 " + pages + " 页: " + idsOf(page).size()
                    + " 条, hasMore=" + page.hasMore() + ", nextCursor=" + page.nextCursor());
            if (!page.hasMore()) {
                break;
            }
            cursor = page.nextCursor();
        }

        System.out.println("  共 " + pages + " 页，收集 " + collected.size() + " 条");
        assertEquals(3, pages, "25 条按每页 10 条应分 3 页");
        assertEquals(25, collected.size(), "★ 必须正好 25 条");
        assertEquals(25, collected.stream().distinct().count(), "★ 不能有重复");
        // 必须严格递减
        for (int i = 1; i < collected.size(); i++) {
            assertTrue(collected.get(i) < collected.get(i - 1), "id 必须严格递减");
        }
        System.out.println("  ✓ 25 条不重不漏，且严格递减");
    }

    @Test
    void newMessageDoesNotShiftAlreadySeenPages() {
        System.out.println("\n===== ★★★ 核心：来了新消息，翻页不能重复/漏 =====");
        // 造 20 条
        for (int i = 1; i <= 20; i++) {
            newMessage(MY_ID, "USER", "TEXT", "消息" + i);
        }

        // 用户看第 1 页（10 条）
        ChatMessagePageVO page1 = chatService.pageMessages(MY_ID, null, 10);
        List<Long> seen = new java.util.ArrayList<>(idsOf(page1));
        Long cursor = page1.nextCursor();
        System.out.println("  第 1 页: " + seen.size() + " 条，nextCursor=" + cursor);

        // ★ 此刻来了 3 条新消息（模拟用户正在浏览时收到推送）
        for (int i = 1; i <= 3; i++) {
            newMessage(MY_ID, "AI", "TEXT", "新消息" + i);
        }
        System.out.println("  → 期间来了 3 条新消息");

        // 用户继续翻第 2 页（用之前的 cursor）
        ChatMessagePageVO page2 = chatService.pageMessages(MY_ID, cursor, 10);
        List<Long> page2Ids = idsOf(page2);
        seen.addAll(page2Ids);
        System.out.println("  第 2 页: " + page2Ids.size() + " 条");

        // ★ 关键断言：20 条旧消息必须全部出现过且只出现一次
        assertEquals(20, seen.size(),
                "★ 20 条旧消息应该一条不漏（新消息是按 cursor 过滤掉的）");
        assertEquals(20, seen.stream().distinct().count(),
                "★★ 不能有重复 —— 这正是 offset 分页会出错的场景");

        // 新消息 id 更大，不该出现在用旧 cursor 翻的页里
        Long maxSeen = seen.stream().max(Long::compare).orElse(0L);
        Long newestId = chatService.pageMessages(MY_ID, null, 1).items().get(0).id();
        assertTrue(newestId > maxSeen, "新消息的 id 应大于旧消息");
        System.out.println("  ✓ 旧消息 20 条不重不漏；新消息 id=" + newestId
                + " 未混入旧 cursor 的翻页（offset 分页在这里会重复）");
    }

    @Test
    void hasMoreIsFalseOnLastPage() {
        System.out.println("\n===== 最后一页 hasMore=false 且 nextCursor=null =====");
        for (int i = 1; i <= 5; i++) {
            newMessage(MY_ID, "USER", "TEXT", "消息" + i);
        }

        ChatMessagePageVO page = chatService.pageMessages(MY_ID, null, 10);

        assertEquals(5, page.items().size());
        assertFalse(page.hasMore(), "5 条 < 每页 10 条 → 没有更多");
        assertNull(page.nextCursor(), "★ 没有更多时 nextCursor 必须是 null");
        System.out.println("  ✓ hasMore=false, nextCursor=null");
    }

    @Test
    void exactlyFullPageHasNoMore() {
        System.out.println("\n===== 边界：正好 10 条、每页 10 条 → hasMore=false =====");
        for (int i = 1; i <= 10; i++) {
            newMessage(MY_ID, "USER", "TEXT", "消息" + i);
        }

        ChatMessagePageVO page = chatService.pageMessages(MY_ID, null, 10);

        System.out.println("  返回 " + page.items().size() + " 条, hasMore=" + page.hasMore());
        assertEquals(10, page.items().size());
        assertFalse(page.hasMore(), "★ 正好装满一页但确实没有更多了");
        assertNull(page.nextCursor());
        System.out.println("  ✓ 多查一条的策略在这里正确判定（没有第 11 条）");
    }

    @Test
    void oneMoreThanPageSizeSetsHasMore() {
        System.out.println("\n===== 边界：11 条、每页 10 条 → hasMore=true =====");
        for (int i = 1; i <= 11; i++) {
            newMessage(MY_ID, "USER", "TEXT", "消息" + i);
        }

        ChatMessagePageVO page = chatService.pageMessages(MY_ID, null, 10);

        System.out.println("  返回 " + page.items().size() + " 条, hasMore=" + page.hasMore());
        assertEquals(10, page.items().size(), "只返回 10 条（第 11 条是探测用的）");
        assertTrue(page.hasMore());
        assertNotNull(page.nextCursor());
        System.out.println("  ✓ 多查的那 1 条被丢掉，hasMore=true");
    }

    @Test
    void cursorBeyondOldestReturnsEmpty() {
        System.out.println("\n===== cursor 小于最小 id → 空页 =====");
        Long only = newMessage(MY_ID, "USER", "TEXT", "唯一一条");

        ChatMessagePageVO page = chatService.pageMessages(MY_ID, only, 10);

        assertEquals(0, page.items().size(), "比最早一条还早的消息不存在");
        assertFalse(page.hasMore());
        System.out.println("  ✓ 返回空页（不是报错）");
    }

    @Test
    void nextCursorEqualsLastItemId() {
        System.out.println("\n===== nextCursor = 本页最后一条的 id =====");
        for (int i = 1; i <= 15; i++) {
            newMessage(MY_ID, "USER", "TEXT", "消息" + i);
        }

        ChatMessagePageVO page = chatService.pageMessages(MY_ID, null, 10);
        Long lastId = page.items().get(page.items().size() - 1).id();

        System.out.println("  本页最后一条 id=" + lastId + "，nextCursor=" + page.nextCursor());
        assertEquals(lastId, page.nextCursor(), "★ 游标必须是本页最旧一条的 id");
        System.out.println("  ✓ 一致（下一页从这条之前开始）");
    }

    // ==================== size 处理 ====================

    @Test
    void defaultSizeIs20() {
        System.out.println("\n===== 不传 size → 默认 20 =====");
        for (int i = 1; i <= 25; i++) {
            newMessage(MY_ID, "USER", "TEXT", "消息" + i);
        }

        ChatMessagePageVO page = chatService.pageMessages(MY_ID, null, null);
        System.out.println("  返回 " + page.items().size() + " 条");
        assertEquals(20, page.items().size());
        assertTrue(page.hasMore());
    }

    @Test
    void sizeIsClampedToMax100() {
        System.out.println("\n===== size=1000 → 被夹到 100（防止拖垮内存）=====");
        for (int i = 1; i <= 120; i++) {
            newMessage(MY_ID, "USER", "TEXT", "消息" + i);
        }

        ChatMessagePageVO page = chatService.pageMessages(MY_ID, null, 1000);
        System.out.println("  返回 " + page.items().size() + " 条");
        assertEquals(100, page.items().size(), "★ 上限必须是 100");
        System.out.println("  ✓ 被夹住（不报错，直接修正）");
    }

    @Test
    void invalidSizeFallsBackToDefault() {
        System.out.println("\n===== size=0 / 负数 → 回落默认 20 =====");
        for (int i = 1; i <= 25; i++) {
            newMessage(MY_ID, "USER", "TEXT", "消息" + i);
        }

        assertEquals(20, chatService.pageMessages(MY_ID, null, 0).items().size());
        assertEquals(20, chatService.pageMessages(MY_ID, null, -5).items().size());
        System.out.println("  ✓ 都是 20 条（分页大小是偏好，不抛 400）");
    }

    // ==================== meta 解析 ====================

    @Test
    void metaJsonIsReturnedAsObject() {
        System.out.println("\n===== ★ meta_json 解析成 JSON 对象（不是字符串）=====");
        ChatMessage msg = new ChatMessage();
        msg.setUserId(MY_ID);
        msg.setBookId(bookId);
        msg.setRole("AI");
        msg.setType("STAT_CARD");
        msg.setContent("本月统计");
        msg.setMetaJson("{\"amount\":1250.00,\"bookName\":\"日常账本\"}");
        msg.setTriggerType("DAILY_OPEN");
        chatMessageMapper.insert(msg);

        ChatMessageVO vo = chatService.pageMessages(MY_ID, null, 20).items().get(0);

        assertNotNull(vo.meta(), "meta 不能为 null");
        assertTrue(vo.meta().isObject(), "★ 必须是 JSON 对象，不是字符串");
        System.out.println("  meta = " + vo.meta());
        System.out.println("  meta.amount = " + vo.meta().get("amount"));
        assertEquals(1250.00, vo.meta().get("amount").asDouble(), 0.001);
        System.out.println("  ✓ 前端可直接读 meta.amount，不用二次 JSON.parse");
    }

    @Test
    void nullMetaIsNull() {
        System.out.println("\n===== 没有 meta 的消息 → meta=null =====");
        newMessage(MY_ID, "USER", "TEXT", "普通文本");

        ChatMessageVO vo = chatService.pageMessages(MY_ID, null, 20).items().get(0);
        assertNull(vo.meta());
        System.out.println("  ✓ null（不是空对象，也不是字符串 \"null\"）");
    }

    @Test
    void nonObjectMetaDoesNotBreakPage() {
        System.out.println("\n===== ★ 非对象 JSON 不能让整个分页接口崩 =====");
        // ★ 注意：t_chat_message.meta_json 是 MySQL 的 json 列，
        //   数据库层面就会拒绝非法 JSON（插不进去），所以"解析失败"实际不可达。
        //   这里退一步测【合法但不是对象】的 JSON —— 例如数组或标量，
        //   它们能存进去，但边界情况一样需要不崩。
        ChatMessage weird = new ChatMessage();
        weird.setUserId(MY_ID);
        weird.setBookId(bookId);
        weird.setRole("AI");
        weird.setType("STAT_CARD");
        weird.setContent("非对象 meta");
        weird.setMetaJson("[1,2,3]");        // 合法 JSON，但是数组不是对象
        chatMessageMapper.insert(weird);

        newMessage(MY_ID, "USER", "TEXT", "正常消息");

        ChatMessagePageVO page = chatService.pageMessages(MY_ID, null, 20);
        System.out.println("  返回 " + page.items().size() + " 条");
        assertEquals(2, page.items().size(), "★ 非对象 meta 的那条仍要出现在列表里");

        ChatMessageVO weirdVo = page.items().stream()
                .filter(v -> "非对象 meta".equals(v.content())).findFirst().orElseThrow();
        assertNotNull(weirdVo.meta(), "合法 JSON 应能解析出来");
        assertTrue(weirdVo.meta().isArray(), "原样保留其结构（数组）");
        System.out.println("  ✓ meta 是数组 " + weirdVo.meta() + "，消息正常返回，没有 500");
    }

    // ==================== 字段完整性 ====================

    @Test
    void allFieldsAreMapped() {
        System.out.println("\n===== 字段映射完整 =====");
        ChatMessage msg = new ChatMessage();
        msg.setUserId(MY_ID);
        msg.setBookId(bookId);
        msg.setRole("AI");
        msg.setType("BILL_CARD");
        msg.setContent("记了一笔");
        msg.setBillId(12345L);
        msg.setMetaJson("{\"amount\":25}");
        msg.setTriggerType("AI_PARSE");
        chatMessageMapper.insert(msg);

        ChatMessageVO vo = chatService.pageMessages(MY_ID, null, 20).items().get(0);

        System.out.println("  " + vo);
        assertEquals("AI", vo.role());
        assertEquals("BILL_CARD", vo.type());
        assertEquals("记了一笔", vo.content());
        assertEquals(12345L, vo.billId());
        assertEquals("AI_PARSE", vo.triggerType());
        assertEquals(bookId, vo.bookId());
        assertNotNull(vo.createTime(), "create_time 由数据库默认值填充，必须能读出来");
        System.out.println("  ✓ role/type/content/billId/triggerType/bookId/createTime 全部正确");
    }
}
