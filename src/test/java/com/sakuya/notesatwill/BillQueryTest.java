package com.sakuya.notesatwill;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Account;
import com.sakuya.notesatwill.entity.Bill;
import com.sakuya.notesatwill.entity.Book;
import com.sakuya.notesatwill.entity.DTO.BillQuery;
import com.sakuya.notesatwill.entity.DTO.BillRequest;
import com.sakuya.notesatwill.entity.VO.BillVO;
import com.sakuya.notesatwill.mapper.AccountMapper;
import com.sakuya.notesatwill.service.BillService;
import com.sakuya.notesatwill.service.BookService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账单分页查询 + 批量补查的回归测试（任务 3.4 / 3.5）。
 *
 * <p>覆盖四类：
 * <ol>
 *   <li>分页正确性（含 size 上限、页码越界）</li>
 *   <li>六个筛选条件（重点是<b>按账户筛不能误选未选账户的账单</b>）</li>
 *   <li>批量补查（名字是否正确、未选账户是否为 null）</li>
 *   <li>越权与数据隔离</li>
 * </ol>
 *
 * @author sakuya
 */
@SpringBootTest
class BillQueryTest {

    private static final Long MY_ID = 987570001L;
    private static final Long OTHER_ID = 987570002L;
    private static final Long CAT_DINING = 1001L;   // 餐饮 EXPENSE
    private static final Long CAT_SALARY = 2001L;   // 职业收入 INCOME

    @Autowired
    private BillService billService;

    @Autowired
    private BookService bookService;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long bookId;
    private Long accA;
    private Long accB;

    @BeforeEach
    void setUp() {
        clean();
        Book book = bookService.createDefaultBook(MY_ID);
        bookId = book.getId();
        accA = newAccount("招行卡", "1000.00");
        accB = newAccount("微信零钱", "500.00");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (Long u : new Long[]{MY_ID, OTHER_ID}) {
            jdbcTemplate.update("DELETE FROM t_account_flow WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_chat_message WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_bill WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_account WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_book WHERE user_id = ?", u);
        }
    }

    private Long newAccount(String name, String balance) {
        Account a = new Account();
        a.setUserId(MY_ID);
        a.setName(name);
        a.setType("CASH");
        a.setInitBalance(new BigDecimal(balance));
        a.setBalance(new BigDecimal(balance));
        accountMapper.insert(a);
        return a.getId();
    }

    private Bill create(String type, String amount, Long accountId, String remark, LocalDateTime time) {
        Long cat = "EXPENSE".equals(type) ? CAT_DINING : CAT_SALARY;
        return billService.createBill(MY_ID, new BillRequest(
                bookId, cat, type, new BigDecimal(amount), time, remark, accountId));
    }

    private BillQuery q() {
        return new BillQuery(null, null, null, null, null, null, null, null, null);
    }

    // ==================== 分页 ====================

    @Test
    void paginationReturnsExactPageAndTotal() {
        System.out.println("\n===== 分页正确性 =====");
        for (int i = 1; i <= 25; i++) {
            create("EXPENSE", "10.00", null, "第" + i + "笔", LocalDateTime.now().minusMinutes(25 - i));
        }

        Page<BillVO> p1 = billService.pageBills(MY_ID,
                new BillQuery(null, null, null, null, null, null, null, 1, 10));

        System.out.printf("  共 25 条，请求第 1 页/10 条 → records=%d, total=%d, pages=%d%n",
                p1.getRecords().size(), p1.getTotal(), p1.getPages());
        assertEquals(10, p1.getRecords().size());
        assertEquals(25, p1.getTotal());
        assertEquals(3, p1.getPages());

        // 最后一页余数
        Page<BillVO> p3 = billService.pageBills(MY_ID,
                new BillQuery(null, null, null, null, null, null, null, 3, 10));
        assertEquals(5, p3.getRecords().size(), "第 3 页应剩 5 条");

        // 越界页返回空而不是全量
        Page<BillVO> p9 = billService.pageBills(MY_ID,
                new BillQuery(null, null, null, null, null, null, null, 99, 10));
        assertEquals(0, p9.getRecords().size(), "★ 越界页应为空");
        assertEquals(25, p9.getTotal(), "total 仍应正确");
        System.out.println("  ✓ 分页精确 + 余数正确 + 越界返回空");
    }

    @Test
    void sizeIsCappedAtMax() {
        System.out.println("\n===== size 上限保护 =====");
        for (int i = 0; i < 5; i++) {
            create("EXPENSE", "1.00", null, "x" + i, LocalDateTime.now());
        }

        BillQuery huge = new BillQuery(null, null, null, null, null, null, null, 1, 999999);
        System.out.println("  传入 size=999999 → safeSize()=" + huge.safeSize());
        assertEquals(BillQuery.MAX_SIZE, huge.safeSize(), "★ 必须被削到上限，否则会拉全表");

        // null / 0 / 负数都应回落到默认值
        assertEquals(BillQuery.DEFAULT_SIZE,
                new BillQuery(null, null, null, null, null, null, null, 1, null).safeSize());
        assertEquals(BillQuery.DEFAULT_SIZE,
                new BillQuery(null, null, null, null, null, null, null, 1, 0).safeSize());
        assertEquals(1, new BillQuery(null, null, null, null, null, null, null, null, null).safePage(),
                "page=null 应回落为 1");
        System.out.println("  ✓ size 上限 100 生效；非法页码/条数有安全默认值");
    }

    // ==================== 筛选条件 ====================

    @Test
    void filterByAccountDoesNotIncludeAccountlessBills() {
        System.out.println("\n===== ★★ 按账户筛选（核心验收点）=====");

        Bill withA = create("EXPENSE", "25.00", accA, "用了招行卡", LocalDateTime.now());
        Bill withB = create("EXPENSE", "30.00", accB, "用了微信", LocalDateTime.now().minusMinutes(1));
        Bill none = create("EXPENSE", "15.00", null, "没选账户", LocalDateTime.now().minusMinutes(2));

        Page<BillVO> r = billService.pageBills(MY_ID,
                new BillQuery(null, null, null, accA, null, null, null, 1, 20));

        Set<Long> ids = r.getRecords().stream().map(BillVO::id).collect(Collectors.toSet());
        System.out.println("  按 accA 筛 → " + ids.size() + " 条");
        assertTrue(ids.contains(withA.getId()), "应含用 accA 的账单");
        assertTrue(!ids.contains(withB.getId()), "不应含用 accB 的账单");
        assertTrue(!ids.contains(none.getId()), "★★ 绝不能含未选账户的账单");
        assertEquals(1, ids.size());
        assertEquals(1, r.getTotal(), "★ total 也应精确（JOIN 会算成虚高）");
        System.out.println("  ✓ 只命中用 accA 的账单；未选账户的未被误选，total 精确");
    }

    @Test
    void filterByType() {
        System.out.println("\n===== 按 type 筛 =====");
        create("EXPENSE", "10.00", null, "支出", LocalDateTime.now());
        create("INCOME", "100.00", null, "收入", LocalDateTime.now().minusMinutes(1));

        Page<BillVO> e = billService.pageBills(MY_ID,
                new BillQuery(null, "EXPENSE", null, null, null, null, null, 1, 20));
        Page<BillVO> i = billService.pageBills(MY_ID,
                new BillQuery(null, "INCOME", null, null, null, null, null, 1, 20));

        System.out.printf("  EXPENSE → %d 条，INCOME → %d 条%n", e.getTotal(), i.getTotal());
        assertEquals(1, e.getTotal());
        assertEquals(1, i.getTotal());
        assertTrue(e.getRecords().stream().allMatch(v -> "EXPENSE".equals(v.type())));
        System.out.println("  ✓ type 筛选正确");
    }

    @Test
    void filterByCategory() {
        System.out.println("\n===== 按分类筛 =====");
        create("EXPENSE", "10.00", null, "餐饮", LocalDateTime.now());
        create("INCOME", "100.00", null, "收入", LocalDateTime.now().minusMinutes(1));

        Page<BillVO> r = billService.pageBills(MY_ID,
                new BillQuery(null, null, CAT_DINING, null, null, null, null, 1, 20));
        System.out.println("  按 CAT_DINING 筛 → " + r.getTotal() + " 条");
        assertEquals(1, r.getTotal());
        assertEquals(CAT_DINING, r.getRecords().get(0).categoryId());
        System.out.println("  ✓ 分类筛选正确");
    }

    @Test
    void filterByTimeRangeIsLeftClosedRightOpen() {
        System.out.println("\n===== 时间区间：左闭右开 =====");

        LocalDateTime base = LocalDateTime.of(2026, 10, 15, 12, 0, 0);
        create("EXPENSE", "1.00", null, "正好在起点", base);                  // 应包含
        create("EXPENSE", "2.00", null, "区间中间", base.plusHours(1));        // 应包含
        create("EXPENSE", "3.00", null, "正好在终点", base.plusHours(2));      // 应排除（右开）
        create("EXPENSE", "4.00", null, "区间之前", base.minusSeconds(1));     // 应排除

        Page<BillVO> r = billService.pageBills(MY_ID, new BillQuery(
                null, null, null, null, base, base.plusHours(2), null, 1, 20));

        List<String> remarks = r.getRecords().stream().map(BillVO::remark).toList();
        System.out.println("  区间 [" + base + ", " + base.plusHours(2) + ")");
        System.out.println("  命中：" + remarks);
        assertEquals(2, r.getTotal(), "应只命中起点与中间两条");
        assertTrue(remarks.contains("正好在起点"), "★ 起点应包含（左闭）");
        assertTrue(remarks.contains("区间中间"));
        assertTrue(!remarks.contains("正好在终点"), "★ 终点应排除（右开）");
        assertTrue(!remarks.contains("区间之前"));
        System.out.println("  ✓ 左闭右开语义正确");
    }

    @Test
    void filterByKeyword() {
        System.out.println("\n===== 关键词筛备注 =====");
        create("EXPENSE", "1.00", null, "公司楼下房租", LocalDateTime.now());
        create("EXPENSE", "2.00", null, "打车费", LocalDateTime.now().minusMinutes(1));

        Page<BillVO> r = billService.pageBills(MY_ID,
                new BillQuery(null, null, null, null, null, null, "房租", 1, 20));
        System.out.println("  关键词'房租' → " + r.getTotal() + " 条");
        assertEquals(1, r.getTotal());
        assertTrue(r.getRecords().get(0).remark().contains("房租"));

        // 空白关键词应被当作"不筛"，而不是匹配空串
        Page<BillVO> blank = billService.pageBills(MY_ID,
                new BillQuery(null, null, null, null, null, null, "   ", 1, 20));
        System.out.println("  空白关键词 → " + blank.getTotal() + " 条（应等同不筛）");
        assertEquals(2, blank.getTotal(), "★ 空白关键词不该被当成条件");
        System.out.println("  ✓ 关键词筛选正确；空白被忽略");
    }

    @Test
    void combinedFilters() {
        System.out.println("\n===== 多条件组合 =====");
        // ★ 注意：bill_time 不能是未来（会被 BILL_TIME_FUTURE 拒绝），
        //   所以用"本月"与"上月"构造跨月数据，而不是写死未来月份。
        LocalDateTime thisMonth = LocalDateTime.now()
                .withDayOfMonth(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime lastMonth = thisMonth.minusMonths(1);

        create("EXPENSE", "10.00", accA, "本月房租", thisMonth.plusDays(4));
        create("EXPENSE", "20.00", accA, "上月房租", lastMonth.plusDays(4));
        create("EXPENSE", "30.00", accB, "本月房租", thisMonth.plusDays(5));
        create("INCOME", "40.00", accA, "本月工资", thisMonth.plusDays(6));

        Page<BillVO> r = billService.pageBills(MY_ID, new BillQuery(
                bookId, "EXPENSE", CAT_DINING, accA,
                thisMonth,                       // 本月 1 日起
                thisMonth.plusMonths(1),         // 次月 1 日止（右开）
                "房租", 1, 20));

        System.out.println("  账本+支出+餐饮+accA+本月+含'房租' → " + r.getTotal() + " 条");
        assertEquals(1, r.getTotal(), "只有一条同时满足全部条件");
        assertEquals("本月房租", r.getRecords().get(0).remark());
        System.out.println("  ✓ 六条件同时生效");
    }

    @Test
    void filterByBook() {
        System.out.println("\n===== 按账本筛 =====");
        create("EXPENSE", "10.00", null, "默认账本", LocalDateTime.now());
        Book other = bookService.create(MY_ID,
                new com.sakuya.notesatwill.entity.DTO.BookRequest("家庭", "home", "#FF0000", null));
        billService.createBill(MY_ID, new BillRequest(
                other.getId(), CAT_DINING, "EXPENSE", new BigDecimal("20.00"),
                LocalDateTime.now(), "家庭账本", null));

        Page<BillVO> mine = billService.pageBills(MY_ID,
                new BillQuery(bookId, null, null, null, null, null, null, 1, 20));
        Page<BillVO> fam = billService.pageBills(MY_ID,
                new BillQuery(other.getId(), null, null, null, null, null, null, 1, 20));

        System.out.printf("  默认账本 → %d 条，家庭 → %d 条%n", mine.getTotal(), fam.getTotal());
        assertEquals(1, mine.getTotal());
        assertEquals(1, fam.getTotal());
        System.out.println("  ✓ 账本筛选正确");
    }

    // ==================== 批量补查（3.5）====================

    @Test
    void voContainsNamesNotJustIds() {
        System.out.println("\n===== 批量补查：id 变名字 =====");
        create("EXPENSE", "25.00", accA, "午餐", LocalDateTime.now());

        BillVO vo = billService.pageBills(MY_ID, q()).getRecords().get(0);
        System.out.printf("  categoryName=%s, bookName=%s, accountName=%s%n",
                vo.categoryName(), vo.bookName(), vo.accountName());

        assertEquals("餐饮", vo.categoryName(), "应补出分类名");
        assertEquals("默认账本", vo.bookName(), "应补出账本名");
        assertEquals("招行卡", vo.accountName(), "★ 应补出账户名（跨表）");
        assertEquals(accA, vo.accountId());
        assertNotNull(vo.createTime());
        System.out.println("  ✓ 三类名字都补齐了");
    }

    @Test
    void accountNameIsNullWhenNoAccountUsed() {
        System.out.println("\n===== 未选账户时 accountId/accountName 为 null =====");
        create("EXPENSE", "25.00", null, "没选账户", LocalDateTime.now());

        BillVO vo = billService.pageBills(MY_ID, q()).getRecords().get(0);
        System.out.printf("  accountId=%s, accountName=%s%n", vo.accountId(), vo.accountName());
        assertNull(vo.accountId(), "未选账户时 accountId 应为 null");
        assertNull(vo.accountName(), "未选账户时 accountName 应为 null");
        // 但分类名/账本名仍然要有
        assertEquals("餐饮", vo.categoryName());
        System.out.println("  ✓ 账户字段为 null（正常情况），其它名字仍补齐 —— 前端需能处理");
    }

    @Test
    void mixedBillsGetCorrectNames() {
        System.out.println("\n===== 混合场景：同一页里有的有账户、有的没有 =====");
        create("EXPENSE", "10.00", accA, "有账户A", LocalDateTime.now());
        create("EXPENSE", "20.00", accB, "有账户B", LocalDateTime.now().minusMinutes(1));
        create("EXPENSE", "30.00", null, "无账户", LocalDateTime.now().minusMinutes(2));

        Page<BillVO> page = billService.pageBills(MY_ID, q());
        System.out.println("  三条账单的账户名：" + page.getRecords().stream()
                .map(v -> v.remark() + "→" + v.accountName()).toList());

        assertEquals(3, page.getTotal());
        BillVO voA = page.getRecords().stream().filter(v -> "有账户A".equals(v.remark())).findFirst().orElseThrow();
        BillVO voB = page.getRecords().stream().filter(v -> "有账户B".equals(v.remark())).findFirst().orElseThrow();
        BillVO voN = page.getRecords().stream().filter(v -> "无账户".equals(v.remark())).findFirst().orElseThrow();

        assertEquals("招行卡", voA.accountName());
        assertEquals("微信零钱", voB.accountName());
        assertNull(voN.accountName());
        System.out.println("  ✓ 每条的账户名都对上号（未串号）");
    }

    @Test
    void pageOrderIsStableForSameTimestamp() {
        System.out.println("\n===== 同一时刻的多笔账单，分页顺序应稳定 =====");

        LocalDateTime same = LocalDateTime.now().withNano(0);
        for (int i = 1; i <= 6; i++) {
            create("EXPENSE", i + ".00", null, "同刻" + i, same);
        }

        // 连查两次，顺序必须一致（靠 id 倒序做 tie-breaker）
        List<Long> first = billService.pageBills(MY_ID,
                new BillQuery(null, null, null, null, null, null, null, 1, 3))
                .getRecords().stream().map(BillVO::id).toList();
        List<Long> second = billService.pageBills(MY_ID,
                new BillQuery(null, null, null, null, null, null, null, 1, 3))
                .getRecords().stream().map(BillVO::id).toList();
        System.out.println("  第一次：" + first);
        System.out.println("  第二次：" + second);
        assertEquals(first, second, "★ 同一时间戳的顺序必须稳定，否则翻页会重复/漏数据");

        // 翻页不重叠：第1页与第2页无交集
        List<Long> p2 = billService.pageBills(MY_ID,
                new BillQuery(null, null, null, null, null, null, null, 2, 3))
                .getRecords().stream().map(BillVO::id).toList();
        Set<Long> overlap = new HashSet<>(first);
        overlap.retainAll(p2);
        assertTrue(overlap.isEmpty(), "★ 相邻页不能有重复记录");
        System.out.println("  ✓ 顺序稳定、相邻页无重叠（id 倒序 tie-breaker 生效）");
    }

    // ==================== 越权 ====================

    @Test
    void cannotSeeOthersBills() {
        System.out.println("\n===== 越权：看不到别人的账单 =====");
        create("EXPENSE", "10.00", null, "我的账单", LocalDateTime.now());

        // 直接给 OTHER 插一条
        jdbcTemplate.update(
                "INSERT INTO t_bill (user_id, book_id, category_id, type, amount, remark, bill_time, source, deleted, create_time, update_time)"
                        + " VALUES (?, 1, 1001, 'EXPENSE', 99.00, '别人的账单', NOW(), 'MANUAL', 0, NOW(), NOW())",
                OTHER_ID);

        Page<BillVO> mine = billService.pageBills(MY_ID, q());
        System.out.println("  MY_ID 查到 " + mine.getTotal() + " 条");
        assertEquals(1, mine.getTotal(), "★ 只能看到自己的账单");
        assertEquals("我的账单", mine.getRecords().get(0).remark());
        System.out.println("  ✓ 数据隔离正确");
    }

    @Test
    void detailOfOthersBillIsRejected() {
        System.out.println("\n===== 越权：查别人的账单详情 =====");
        jdbcTemplate.update(
                "INSERT INTO t_bill (user_id, book_id, category_id, type, amount, remark, bill_time, source, deleted, create_time, update_time)"
                        + " VALUES (?, 1, 1001, 'EXPENSE', 99.00, '别人的', NOW(), 'MANUAL', 0, NOW(), NOW())",
                OTHER_ID);
        Long othersId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_bill WHERE user_id = ? LIMIT 1", Long.class, OTHER_ID);

        BusinessException e = assertThrows(BusinessException.class,
                () -> billService.getBill(MY_ID, othersId));
        assertEquals(ErrorCode.BILL_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  查别人的账单 → 被拒(" + e.getCode() + ") ✓");
    }

    @Test
    void emptyResultReturnsEmptyListNotError() {
        System.out.println("\n===== 查不到时返回空列表而非报错 =====");
        Page<BillVO> r = billService.pageBills(MY_ID,
                new BillQuery(null, null, null, null, null, null, "不存在的关键词xyz", 1, 20));

        assertNotNull(r.getRecords(), "records 不应为 null");
        assertTrue(r.getRecords().isEmpty());
        assertEquals(0, r.getTotal());
        System.out.println("  ✓ 返回空 records + total=0，未抛异常");
    }
}
