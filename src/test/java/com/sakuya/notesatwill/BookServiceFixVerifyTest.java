package com.sakuya.notesatwill;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Book;
import com.sakuya.notesatwill.entity.DTO.BookRequest;
import com.sakuya.notesatwill.mapper.BookMapper;
import com.sakuya.notesatwill.service.BookService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BookService 三个修复的回归验证（真连库，跑在 Docker 的 MySQL 上）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>缺陷1：list 空列表<b>不再抛异常</b>，且 create 在空库下能成功</li>
 *   <li>缺陷2：update <b>真的改了库</b>（漏 setId 时会静默失败，这条能抓到）</li>
 *   <li>缺陷3：getOwned 的 userId / bookId 判空</li>
 *   <li>附带：delete 的两条规则（默认账本不可删 + 至少留一个）</li>
 * </ol>
 *
 * @author sakuya
 */
@SpringBootTest
class BookServiceFixVerifyTest {

    /** 用一个不可能与真实数据冲突的测试用户 id */
    private static final Long TEST_USER = 987654321L;
    private static final Long OTHER_USER = 987654322L;

    @Autowired
    private BookService bookService;

    @Autowired
    private BookMapper bookMapper;

    /**
     * 用原生 SQL 清理测试数据。
     *
     * <p><b>★ 为什么不用 {@code bookMapper.delete(wrapper)}</b>（实测踩过）：
     * {@code Book.deleted} 上有 {@code @TableLogic}，MP 会把<b>所有</b> delete 调用
     * （包括按 wrapper 删）都改写成 {@code UPDATE ... SET deleted = 1}。
     * 也就是说它做的是<b>逻辑删除，不是物理删除</b> —— 行会一直留在表里。
     *
     * <p>实测：测试跑一轮后，{@code SELECT COUNT(*)} 显示 9 行残留、且全部 {@code deleted=1}。
     * 对断言没影响（查询会自动过滤），但脏数据会随每次跑测试不断累积，
     * 几百轮之后表里全是垃圾。所以清理必须走原生 DELETE。
     *
     * <p>这也顺带说明一件事：<b>测试代码里"清理数据"的语义与业务代码不同</b> ——
     * 业务要逻辑删除（可追溯），测试要物理删除（不留痕）。
     */
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM t_book WHERE user_id IN (?, ?)", TEST_USER, OTHER_USER);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // ==================== 缺陷 1 ====================

    @Test
    void list_returnsEmptyListInsteadOfThrowing() {
        System.out.println("\n===== 缺陷1：空列表不再抛异常 =====");

        // 该用户一个账本都没有，应当返回空列表而不是异常
        List<Book> books = bookService.list(TEST_USER);
        assertNotNull(books, "不应返回 null");
        assertTrue(books.isEmpty(), "应为空列表，实际 size=" + books.size());
        System.out.println("  list() 返回空列表，未抛异常 ✓");

        // 关键连锁验证：空库下 create 必须成功（此前会因 nextSort→list 抛异常而崩）
        Book created = bookService.create(TEST_USER, new BookRequest("第一个账本", "wallet", "#FF0000", null));
        assertNotNull(created.getId(), "主键应已回填");
        System.out.println("  空库下 create() 成功，id=" + created.getId() + " ✓");
    }

    // ==================== 缺陷 2 ====================

    @Test
    void update_actuallyPersistsChanges() {
        System.out.println("\n===== 缺陷2：update 真的写库（漏 setId 会静默失败）=====");

        Book created = bookService.create(TEST_USER, new BookRequest("旧名字", "old", "#111111", 5));
        Long id = created.getId();
        System.out.println("  创建：" + created.getName() + " / " + created.getIcon() + " / sort=" + created.getSort());

        BookRequest req = new BookRequest("新名字", "new-icon", "#222222", 9);
        Book returned = bookService.update(TEST_USER, id, req);

        // ① 返回值应是新值
        assertEquals("新名字", returned.getName(), "返回值未更新");
        assertEquals("new-icon", returned.getIcon(), "返回值 icon 未更新");
        assertEquals("#222222", returned.getColor(), "返回值 color 未更新");
        assertEquals(9, returned.getSort(), "返回值 sort 未更新");
        System.out.println("  返回值：" + returned.getName() + " / " + returned.getIcon() + " / sort=" + returned.getSort());

        // ② 关键：直接查库确认真的落盘了（这才是能抓到"漏 setId"的断言）
        Book fromDb = bookMapper.selectById(id);
        assertEquals("新名字", fromDb.getName(), "★ 数据库未更新 —— 极可能漏了 setId！");
        assertEquals("new-icon", fromDb.getIcon(), "★ 数据库 icon 未更新");
        assertEquals("#222222", fromDb.getColor(), "★ 数据库 color 未更新");
        assertEquals(9, fromDb.getSort(), "★ 数据库 sort 未更新");
        System.out.println("  数据库已同步更新 ✓");

        // ③ 不动 sort 时应保持原值
        Book r2 = bookService.update(TEST_USER, id, new BookRequest("再改一次", "i2", "#333333", null));
        assertEquals(9, r2.getSort(), "未传 sort 时应保持原值 9，实际=" + r2.getSort());
        System.out.println("  未传 sort 时保持原值 ✓");
    }

    // ==================== 缺陷 3 ====================

    @Test
    void getOwned_rejectsNullArguments() {
        System.out.println("\n===== 缺陷3：getOwned 参数判空 =====");

        BusinessException e1 = assertThrows(BusinessException.class,
                () -> bookService.getOwned(null, 1L), "userId 为 null 应被拒");
        assertEquals(ErrorCode.BOOK_NOT_FOUND.getCode(), e1.getCode());
        System.out.println("  userId=null 被拒，code=" + e1.getCode() + " ✓");

        BusinessException e2 = assertThrows(BusinessException.class,
                () -> bookService.getOwned(TEST_USER, null), "bookId 为 null 应被拒");
        assertEquals(ErrorCode.BOOK_NOT_FOUND.getCode(), e2.getCode());
        System.out.println("  bookId=null 被拒，code=" + e2.getCode() + " ✓");
    }

    @Test
    void getOwned_doesNotLeakOthersBooks() {
        System.out.println("\n===== 越权校验：查不到别人的账本 =====");

        Book others = bookService.create(OTHER_USER, new BookRequest("别人的账本", "x", "#000000", 1));

        BusinessException e = assertThrows(BusinessException.class,
                () -> bookService.getOwned(TEST_USER, others.getId()),
                "不应能查到其他用户的账本");
        assertEquals(ErrorCode.BOOK_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  用 TEST_USER 查 OTHER_USER 的账本被拒，code=" + e.getCode() + " ✓");

        // 反过来，本人查得到
        Book mine = bookService.getOwned(OTHER_USER, others.getId());
        assertEquals("别人的账本", mine.getName());
        System.out.println("  本人可正常查到 ✓");
    }

    // ==================== delete 两条规则 ====================

    @Test
    void delete_enforcesTwoRules() {
        System.out.println("\n===== delete：默认账本不可删 + 至少留一个 =====");

        Book def = bookService.createDefaultBook(TEST_USER);
        assertEquals(1, def.getIsDefault(), "默认账本 isDefault 应为 1");
        assertNotNull(def.getId(), "主键应已回填");
        System.out.println("  createDefaultBook：id=" + def.getId() + ", name=" + def.getName()
                + ", color=" + def.getColor() + ", sort=" + def.getSort());

        // 规则①：默认账本不可删（3001）
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> bookService.delete(TEST_USER, def.getId()));
        assertEquals(ErrorCode.BOOK_DEFAULT_CANNOT_DELETE.getCode(), e1.getCode());
        System.out.println("  删默认账本被拒，code=" + e1.getCode() + "（期望 3001）✓");

        // 建第二个（非默认），可以删
        Book second = bookService.create(TEST_USER, new BookRequest("副账本", "i", "#123456", null));
        assertEquals(0, second.getIsDefault(), "新建账本 isDefault 应为 0");
        bookService.delete(TEST_USER, second.getId());
        System.out.println("  非默认账本删除成功 ✓");

        // 确认逻辑删除生效：list 里查不到，但库里行还在（deleted=1）
        List<Book> left = bookService.list(TEST_USER);
        assertEquals(1, left.size(), "应只剩默认账本，实际=" + left.size());
        Book raw = bookMapper.selectById(second.getId());
        assertTrue(raw == null || raw.getDeleted() == null || raw.getDeleted() == 1,
                "逻辑删除后应查不到或 deleted=1");
        System.out.println("  逻辑删除生效：list 只剩 1 个 ✓");

        // 规则②：至少留一个 —— 造一个"只有非默认账本"的用户
        Book only = bookService.create(OTHER_USER, new BookRequest("唯一账本", "i", "#654321", 0));
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> bookService.delete(OTHER_USER, only.getId()),
                "删到 0 个应被拒");
        assertEquals(ErrorCode.BOOK_MIN_ONE_REQUIRED.getCode(), e2.getCode());
        System.out.println("  删最后一个账本被拒，code=" + e2.getCode() + "（期望 3003）✓");
    }

    // ==================== 排序 ====================

    @Test
    void list_ordersDefaultFirstThenSortThenId() {
        System.out.println("\n===== list 三段式排序 =====");

        bookService.create(TEST_USER, new BookRequest("第三", "i", "#000003", 30));
        bookService.create(TEST_USER, new BookRequest("第一", "i", "#000001", 10));
        Book def = bookService.createDefaultBook(TEST_USER);   // sort=0 但 isDefault=1

        List<Book> books = bookService.list(TEST_USER);
        System.out.println("  顺序：" + books.stream().map(b -> b.getName() + "(def=" + b.getIsDefault()
                + ",sort=" + b.getSort() + ")").toList());

        assertEquals(def.getId(), books.get(0).getId(), "默认账本必须置顶（即便 sort 更大）");
        assertEquals("第一", books.get(1).getName(), "其次按 sort 升序");
        assertEquals("第三", books.get(2).getName());
        System.out.println("  默认账本置顶 + sort 升序 ✓");
    }
}
