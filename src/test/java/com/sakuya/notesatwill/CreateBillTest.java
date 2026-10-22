package com.sakuya.notesatwill;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Account;
import com.sakuya.notesatwill.entity.Bill;
import com.sakuya.notesatwill.entity.Book;
import com.sakuya.notesatwill.entity.DTO.BillRequest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code BillService.createBill} 的回归测试。
 *
 * <p>最关键的两条：
 * <ol>
 *   <li><b>不传 accountId → 账户余额纹丝不动、流水 0 条</b>（产品模型的核心）</li>
 *   <li><b>传 accountId → 1 条 BILL 流水 + 余额按方向变动</b></li>
 * </ol>
 *
 * <p>其余覆盖：type 校验、分类可见性与 type 一致性、时间上下界、金额校验。
 *
 * <p>用真实库，因为要验证跨表一致性（t_bill + t_account_flow + t_account）。
 *
 * @author sakuya
 */
@SpringBootTest
class CreateBillTest {

    private static final Long MY_ID = 987590001L;
    private static final Long OTHER_ID = 987590002L;

    /** 系统预置分类 id：餐饮（EXPENSE） */
    private static final Long CAT_DINING = 1001L;
    /** 系统预置分类 id：职业收入（INCOME） */
    private static final Long CAT_SALARY = 2001L;

    @Autowired
    private BillService billService;

    @Autowired
    private BookService bookService;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long bookId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        clean();
        Book book = bookService.createDefaultBook(MY_ID);
        bookId = book.getId();

        Account a = new Account();
        a.setUserId(MY_ID);
        a.setName("现金");
        a.setType("CASH");
        a.setInitBalance(new BigDecimal("1000.00"));
        a.setBalance(new BigDecimal("1000.00"));
        accountMapper.insert(a);
        accountId = a.getId();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM t_account_flow WHERE user_id IN (?, ?)", MY_ID, OTHER_ID);
        jdbcTemplate.update("DELETE FROM t_chat_message WHERE user_id = ?", MY_ID);
        jdbcTemplate.update("DELETE FROM t_bill WHERE user_id IN (?, ?)", MY_ID, OTHER_ID);
        jdbcTemplate.update("DELETE FROM t_account WHERE user_id IN (?, ?)", MY_ID, OTHER_ID);
        jdbcTemplate.update("DELETE FROM t_book WHERE user_id IN (?, ?)", MY_ID, OTHER_ID);
    }

    private BigDecimal balanceOf(Long id) {
        return jdbcTemplate.queryForObject("SELECT balance FROM t_account WHERE id = ?", BigDecimal.class, id);
    }

    private int flowCountOfBill(Long billId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_account_flow WHERE bill_id = ? AND flow_type = 'BILL'",
                Integer.class, billId);
        return n == null ? 0 : n;
    }

    private BillRequest expense(BigDecimal amount, Long accId) {
        return new BillRequest(bookId, CAT_DINING, "EXPENSE", amount, LocalDateTime.now(), "午餐", accId);
    }

    // ==================== ★ 核心：账户选填 ====================

    @Test
    void withoutAccountIdTouchesNothing() {
        System.out.println("\n===== ★ 不传 accountId：不碰任何账户 =====");
        BigDecimal before = balanceOf(accountId);

        Bill bill = billService.createBill(MY_ID, expense(new BigDecimal("25.00"), null));

        assertNotNull(bill.getId(), "账单应入库并回填 id");
        assertEquals(0, flowCountOfBill(bill.getId()), "★ 不应产生任何资金流水");
        assertEquals(0, balanceOf(accountId).compareTo(before), "★ 余额必须纹丝不动");

        System.out.printf("  账单 id=%d，流水 %d 条，余额 %s → %s%n",
                bill.getId(), flowCountOfBill(bill.getId()), before, balanceOf(accountId));
        System.out.println("  ✓ 纯消费记录：只写 1 条 t_bill，账户完全不受影响");
    }

    @Test
    void withAccountIdCreatesFlowAndChangesBalance() {
        System.out.println("\n===== ★ 传 accountId：产生流水 + 余额变动 =====");
        BigDecimal before = balanceOf(accountId);

        Bill bill = billService.createBill(MY_ID, expense(new BigDecimal("25.00"), accountId));

        assertEquals(1, flowCountOfBill(bill.getId()), "应产生 1 条 BILL 流水");
        BigDecimal after = balanceOf(accountId);
        assertEquals(0, after.compareTo(new BigDecimal("975.00")), "支出应扣减余额");

        System.out.printf("  余额 %s → %s，流水 1 条%n", before, after);

        // 校验流水的方向与金额
        String dir = jdbcTemplate.queryForObject(
                "SELECT direction FROM t_account_flow WHERE bill_id = ?", String.class, bill.getId());
        BigDecimal flowAmount = jdbcTemplate.queryForObject(
                "SELECT amount FROM t_account_flow WHERE bill_id = ?", BigDecimal.class, bill.getId());
        System.out.printf("  流水：direction=%s, amount=%s%n", dir, flowAmount);
        assertEquals("OUT", dir, "★ 支出对应 OUT");
        assertEquals(0, flowAmount.compareTo(new BigDecimal("25.00")));
        System.out.println("  ✓ 支出 → OUT，余额正确扣减");
    }

    @Test
    void incomeDirectionIsIn() {
        System.out.println("\n===== 收入账单的方向应为 IN =====");
        BigDecimal before = balanceOf(accountId);

        Bill bill = billService.createBill(MY_ID, new BillRequest(
                bookId, CAT_SALARY, "INCOME", new BigDecimal("500.00"),
                LocalDateTime.now(), "工资", accountId));

        String dir = jdbcTemplate.queryForObject(
                "SELECT direction FROM t_account_flow WHERE bill_id = ?", String.class, bill.getId());
        BigDecimal after = balanceOf(accountId);

        System.out.printf("  direction=%s，余额 %s → %s%n", dir, before, after);
        assertEquals("IN", dir, "★ 收入对应 IN");
        assertEquals(0, after.compareTo(new BigDecimal("1500.00")), "收入应增加余额");
        System.out.println("  ✓ 收入 → IN，方向正确（未写反）");
    }

    @Test
    void flowTimeEqualsBillTime() {
        System.out.println("\n===== flow_time 必须与 bill_time 一致 =====");
        LocalDateTime t = LocalDateTime.now().minusDays(2).withNano(0);

        Bill bill = billService.createBill(MY_ID, new BillRequest(
                bookId, CAT_DINING, "EXPENSE", new BigDecimal("30.00"), t, "补记", accountId));

        String billTime = jdbcTemplate.queryForObject(
                "SELECT bill_time FROM t_bill WHERE id = ?", String.class, bill.getId());
        String flowTime = jdbcTemplate.queryForObject(
                "SELECT flow_time FROM t_account_flow WHERE bill_id = ?", String.class, bill.getId());

        System.out.printf("  bill_time=%s%n  flow_time=%s%n", billTime, flowTime);
        assertEquals(billTime, flowTime, "★ 两者必须一致（列注释要求），否则按时间筛选与对账会错位");
        System.out.println("  ✓ 时间一致");
    }

    // ==================== ② type 校验 ====================

    @Test
    void invalidTypeIsRejected() {
        System.out.println("\n===== ② type 必须合法 =====");

        for (String bad : new String[]{"TRANSFER", "transfer", "expense", "", "XX"}) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> billService.createBill(MY_ID, new BillRequest(
                            bookId, CAT_DINING, bad, new BigDecimal("10.00"), LocalDateTime.now(), null, null)),
                    "type=" + bad + " 应被拒");
            assertEquals(ErrorCode.BILL_TYPE_INVALID.getCode(), e.getCode());
            System.out.println("  type=\"" + bad + "\" → 被拒(" + e.getCode() + ")");
        }
        System.out.println("  ✓ 非法 type 全部拒绝（含 TRANSFER —— 转账不在记账表里）");
    }

    // ==================== ⑤ 分类校验 ====================

    @Test
    void nonexistentCategoryIsRejected() {
        System.out.println("\n===== ⑤ 分类必须存在 =====");

        BusinessException e = assertThrows(BusinessException.class,
                () -> billService.createBill(MY_ID, new BillRequest(
                        bookId, 99999999L, "EXPENSE", new BigDecimal("10.00"),
                        LocalDateTime.now(), null, null)));
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  不存在的分类 → 被拒(" + e.getCode() + ") ✓");
    }

    @Test
    void othersCategoryIsNotVisible() {
        System.out.println("\n===== ⑤ 别人的自建分类不可见 =====");

        // 直接插一条属于 OTHER_ID 的分类
        jdbcTemplate.update(
                "INSERT INTO t_category (user_id, parent_id, name, icon, type, sort, deleted, create_time, update_time)"
                        + " VALUES (?, 0, '别人的分类', 'x', 'EXPENSE', 99, 0, NOW(), NOW())", OTHER_ID);
        Long othersCatId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_category WHERE user_id = ? LIMIT 1", Long.class, OTHER_ID);

        BusinessException e = assertThrows(BusinessException.class,
                () -> billService.createBill(MY_ID, new BillRequest(
                        bookId, othersCatId, "EXPENSE", new BigDecimal("10.00"),
                        LocalDateTime.now(), null, null)));
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), e.getCode());
        jdbcTemplate.update("DELETE FROM t_category WHERE user_id = ?", OTHER_ID);
        System.out.println("  用别人的分类记账 → 被拒(" + e.getCode() + ") ✓");
    }

    @Test
    void categoryTypeMustMatchBillType() {
        System.out.println("\n===== ⑤★ 分类 type 必须与账单 type 一致 =====");

        // 账单说 EXPENSE，分类给 INCOME 的（2001=职业收入）
        BusinessException e = assertThrows(BusinessException.class,
                () -> billService.createBill(MY_ID, new BillRequest(
                        bookId, CAT_SALARY, "EXPENSE", new BigDecimal("10.00"),
                        LocalDateTime.now(), null, null)));
        assertEquals(ErrorCode.CATEGORY_TYPE_MISMATCH.getCode(), e.getCode());
        System.out.println("  EXPENSE 账单 + INCOME 分类 → 被拒(" + e.getCode() + ") ✓");

        // 反向
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> billService.createBill(MY_ID, new BillRequest(
                        bookId, CAT_DINING, "INCOME", new BigDecimal("10.00"),
                        LocalDateTime.now(), null, null)));
        assertEquals(ErrorCode.CATEGORY_TYPE_MISMATCH.getCode(), e2.getCode());
        System.out.println("  INCOME 账单 + EXPENSE 分类 → 被拒(" + e2.getCode() + ") ✓");
        System.out.println("  （数据库无此约束，只能靠 Service 把关）");
    }

    // ==================== ① 金额 ③ 时间 ④ 账本 ====================

    @Test
    void invalidAmountIsRejected() {
        System.out.println("\n===== ① 金额必须 > 0 =====");
        for (String bad : new String[]{"0", "0.00", "-5.00"}) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> billService.createBill(MY_ID, new BillRequest(
                            bookId, CAT_DINING, "EXPENSE", new BigDecimal(bad),
                            LocalDateTime.now(), null, null)));
            assertEquals(ErrorCode.BILL_AMOUNT_INVALID.getCode(), e.getCode());
            System.out.println("  金额 " + bad + " → 被拒");
        }
        System.out.println("  ✓ 零与负数被拒");
    }

    @Test
    void futureBillTimeIsRejectedButSlightSkewIsTolerated() {
        System.out.println("\n===== ③ 时间上界：拒绝未来，容忍时钟偏移 =====");

        // 明显未来 → 拒绝
        BusinessException e = assertThrows(BusinessException.class,
                () -> billService.createBill(MY_ID, new BillRequest(
                        bookId, CAT_DINING, "EXPENSE", new BigDecimal("10.00"),
                        LocalDateTime.now().plusDays(1), null, null)));
        assertEquals(ErrorCode.BILL_TIME_FUTURE.getCode(), e.getCode());
        System.out.println("  明天 → 被拒(" + e.getCode() + ") ✓");

        // 未来 30 秒（模拟客户端时钟偏快）→ 应容忍
        Bill ok = billService.createBill(MY_ID, new BillRequest(
                bookId, CAT_DINING, "EXPENSE", new BigDecimal("10.00"),
                LocalDateTime.now().plusSeconds(30), null, null));
        assertNotNull(ok.getId());
        System.out.println("  未来 30 秒 → 接受（1 分钟容差生效）✓");
    }

    @Test
    void pastBillTimeIsAllowedForBackfill() {
        System.out.println("\n===== ③ 时间下界：允许补记过去 =====");

        LocalDateTime lastWeek = LocalDateTime.now().minusDays(7);
        Bill bill = billService.createBill(MY_ID, new BillRequest(
                bookId, CAT_DINING, "EXPENSE", new BigDecimal("66.00"), lastWeek, "补记上周", null));

        String stored = jdbcTemplate.queryForObject(
                "SELECT bill_time FROM t_bill WHERE id = ?", String.class, bill.getId());
        System.out.println("  写入时间 = " + stored);
        assertNotNull(bill.getId());
        System.out.println("  ✓ 补记过去成功（bill_time 与 create_time 是两个概念）");
    }

    @Test
    void nullBillTimeIsRejected() {
        System.out.println("\n===== ③ billTime 必填（设计 Y）=====");

        // 契约是"必填"：前端总会带值，所以缺了就该明确拒绝，
        // 而不是静默兜底成当前时间 —— 后者会掩盖前端 bug。
        BusinessException e = assertThrows(BusinessException.class,
                () -> billService.createBill(MY_ID, new BillRequest(
                        bookId, CAT_DINING, "EXPENSE", new BigDecimal("10.00"), null, "未传时间", null)),
                "★ 未传 billTime 应被拒绝");
        assertEquals(ErrorCode.BILL_TIME_REQUIRED.getCode(), e.getCode());

        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_bill WHERE user_id = ?", Integer.class, MY_ID);
        System.out.println("  未传时间 → 被拒(" + e.getCode() + ")，库中账单数=" + count);
        assertEquals(0, count, "被拒时不应写入任何账单");
        System.out.println("  ✓ 明确拒绝而非静默兜底（Service 层也判，不依赖 Controller 的 @Valid）");
    }

    @Test
    void othersBookIsRejected() {
        System.out.println("\n===== ④ 账本必须是自己的 =====");

        Book othersBook = bookService.createDefaultBook(OTHER_ID);
        BusinessException e = assertThrows(BusinessException.class,
                () -> billService.createBill(MY_ID, new BillRequest(
                        othersBook.getId(), CAT_DINING, "EXPENSE", new BigDecimal("10.00"),
                        LocalDateTime.now(), null, null)));
        assertEquals(ErrorCode.BOOK_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  用别人的账本 → 被拒(" + e.getCode() + ") ✓");
    }

    @Test
    void nonexistentAccountRollsBackEverything() {
        System.out.println("\n===== 账户不存在时应整体回滚（账单也不留）=====");
        int billsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_bill WHERE user_id = ?", Integer.class, MY_ID);

        BusinessException e = assertThrows(BusinessException.class,
                () -> billService.createBill(MY_ID, expense(new BigDecimal("10.00"), 99999999L)));
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND.getCode(), e.getCode());

        int billsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_bill WHERE user_id = ?", Integer.class, MY_ID);
        System.out.printf("  被拒(%d)，账单数 %d → %d%n", e.getCode(), billsBefore, billsAfter);
        assertEquals(billsBefore, billsAfter, "★ 账户非法时账单必须回滚，不能留下孤儿记录");
        System.out.println("  ✓ 事务回滚完整（验证 @Transactional 生效）");
    }

    @Test
    void sourceIsAlwaysManual() {
        System.out.println("\n===== source 由服务端定为 MANUAL =====");
        Bill bill = billService.createBill(MY_ID, expense(new BigDecimal("10.00"), null));
        String src = jdbcTemplate.queryForObject(
                "SELECT source FROM t_bill WHERE id = ?", String.class, bill.getId());
        assertEquals("MANUAL", src);
        System.out.println("  source = " + src + " ✓（DTO 里没有这个字段，前端无法伪造）");
    }

    @Test
    void deletedIsZeroAfterInsert() {
        System.out.println("\n===== deleted 由数据库默认值填 0 =====");
        Bill bill = billService.createBill(MY_ID, expense(new BigDecimal("10.00"), null));
        Integer del = jdbcTemplate.queryForObject(
                "SELECT deleted FROM t_bill WHERE id = ?", Integer.class, bill.getId());
        assertEquals(0, del, "★ 若为 NULL，@TableLogic 的 deleted=0 过滤会失效");
        System.out.println("  deleted = " + del + " ✓");
    }
}
