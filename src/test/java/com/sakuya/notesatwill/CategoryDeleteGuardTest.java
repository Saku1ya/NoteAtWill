package com.sakuya.notesatwill;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Bill;
import com.sakuya.notesatwill.entity.Category;
import com.sakuya.notesatwill.entity.DTO.BillRequest;
import com.sakuya.notesatwill.entity.DTO.CategoryRequest;
import com.sakuya.notesatwill.mapper.CategoryMapper;
import com.sakuya.notesatwill.service.BillService;
import com.sakuya.notesatwill.service.BookService;
import com.sakuya.notesatwill.service.CategoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除分类的<b>四道护栏</b>回归测试。
 *
 * <p>此前 {@code deleteCategory} <b>完全没有测试覆盖</b>，
 * 导致「该分类下还有账单时拒绝删除」（{@code CATEGORY_HAS_BILLS 3014}）
 * 这个检查一直被 TODO 挂着也没人发现。本类把四道护栏全部钉死。
 *
 * <p>四道护栏<b>顺序敏感</b>：
 * <pre>
 *   ① 不存在 / 不是我的      → 3010（合并，防 id 枚举）
 *   ② 系统预置               → 3011（所有用户共享，不可删）
 *   ③ 还有子分类             → 3013（不做级联删）
 *   ④ 还有账单在用           → 3014（否则账单指向已删分类）
 * </pre>
 * 顺序错了会导致报错码不符合预期（例如"有子分类又有账单"应该报 3013 而不是 3014）。
 *
 * @author sakuya
 */
@SpringBootTest
class CategoryDeleteGuardTest {

    private static final Long MY_ID = 987492001L;
    private static final Long OTHER_ID = 987492002L;
    /** 系统预置分类（user_id IS NULL） */
    private static final Long PRESET_CAT = 1001L;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private BillService billService;

    @Autowired
    private BookService bookService;

    @Autowired
    private com.sakuya.notesatwill.service.BudgetService budgetService;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long bookId;

    @BeforeEach
    void setUp() {
        clean();
        bookId = bookService.createDefaultBook(MY_ID).getId();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (Long u : new Long[]{MY_ID, OTHER_ID}) {
            // ★ 删除顺序按依赖倒序：先删引用方（预算/流水/账单），再删被引用方（账户/账本/分类）。
            //   漏删 t_budget 会让每次运行都往库里留垃圾 —— 这些预算指向已删分类，
            //   测试照样绿，但数据越积越多。D7 加预算用例时忘了补这一行，实测发现。
            jdbcTemplate.update("DELETE FROM t_budget WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_account_flow WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_chat_message WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_bill WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_account WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_book WHERE user_id = ?", u);
            // 自建分类用物理清理（logic delete 会让它留在库里干扰下一个用例）
            jdbcTemplate.update("DELETE FROM t_category WHERE user_id = ?", u);
        }
    }

    private Long newCategory(Long userId, String name, Long parentId, String type) {
        Category c = categoryService.createCategory(userId,
                new CategoryRequest(parentId, name, null, null, type));
        return c.getId();
    }

    private Bill newBill(Long categoryId) {
        return billService.createBill(MY_ID, new BillRequest(
                bookId, categoryId, "EXPENSE", new BigDecimal("25.00"),
                LocalDateTime.now().minusHours(1), "测试", null));
    }

    private int deletedFlag(Long categoryId) {
        Integer d = jdbcTemplate.queryForObject(
                "SELECT deleted FROM t_category WHERE id = ?", Integer.class, categoryId);
        return d == null ? -1 : d;
    }

    // ==================== ① 不存在 / 不是我的 ====================

    @Test
    void cannotDeleteNonexistentCategory() {
        System.out.println("\n===== ① 不存在的分类 → 3010 =====");
        BusinessException e = assertThrows(BusinessException.class,
                () -> categoryService.deleteCategory(MY_ID, 99999999L));
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  被拒(" + e.getCode() + ") ✓");
    }

    @Test
    void cannotDeleteOthersCategory() {
        System.out.println("\n===== ① 别人的分类 → 3010（与不存在同码，防枚举）=====");
        Long othersCat = newCategory(OTHER_ID, "别人的分类", 0L, "EXPENSE");

        BusinessException e = assertThrows(BusinessException.class,
                () -> categoryService.deleteCategory(MY_ID, othersCat));

        assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), e.getCode(),
                "★ 不能因为存在就泄露『这个 id 有效』");
        assertEquals(0, deletedFlag(othersCat), "★ 别人的分类不能被删");
        System.out.println("  被拒(" + e.getCode() + ")，别人的分类未被删除 ✓");
    }

    // ==================== ② 系统预置 ====================

    @Test
    void cannotDeletePresetCategory() {
        System.out.println("\n===== ② 系统预置分类 → 3011 =====");
        BusinessException e = assertThrows(BusinessException.class,
                () -> categoryService.deleteCategory(MY_ID, PRESET_CAT));
        assertEquals(ErrorCode.CATEGORY_PRESET_READONLY.getCode(), e.getCode());
        assertEquals(0, deletedFlag(PRESET_CAT), "★ 预置分类是所有用户共享的，绝不能删");
        System.out.println("  被拒(" + e.getCode() + ")，预置分类完好 ✓");
    }

    // ==================== ③ 子分类 ====================

    @Test
    void cannotDeleteCategoryWithChildren() {
        System.out.println("\n===== ③ 还有子分类 → 3013（不做级联删）=====");
        Long parent = newCategory(MY_ID, "父分类", 0L, "EXPENSE");
        newCategory(MY_ID, "子分类", parent, "EXPENSE");

        BusinessException e = assertThrows(BusinessException.class,
                () -> categoryService.deleteCategory(MY_ID, parent));

        assertEquals(ErrorCode.CATEGORY_HAS_CHILDREN.getCode(), e.getCode());
        assertEquals(0, deletedFlag(parent));
        System.out.println("  被拒(" + e.getCode() + ")，父分类仍在 ✓");
    }

    // ==================== ★ ④ 账单在用（本次补上的护栏）====================

    @Test
    void cannotDeleteCategoryStillUsedByBills() {
        System.out.println("\n===== ★★ ④ 还有账单在用 → 3014（本次新补的护栏）=====");
        Long cat = newCategory(MY_ID, "会被账单引用的分类", 0L, "EXPENSE");
        newBill(cat);

        BusinessException e = assertThrows(BusinessException.class,
                () -> categoryService.deleteCategory(MY_ID, cat));

        System.out.println("  被拒(" + e.getCode() + ")：" + e.getMessage());
        assertEquals(ErrorCode.CATEGORY_HAS_BILLS.getCode(), e.getCode());
        assertEquals(0, deletedFlag(cat), "★ 分类不能真被删掉");
        System.out.println("  ✓ 分类未被删除，账单仍能显示正确的分类名");
    }

    @Test
    void multipleBillsStillBlockDeletion() {
        System.out.println("\n===== ④ 多笔账单同样拦住 =====");
        Long cat = newCategory(MY_ID, "多笔引用", 0L, "EXPENSE");
        newBill(cat);
        newBill(cat);
        newBill(cat);

        BusinessException e = assertThrows(BusinessException.class,
                () -> categoryService.deleteCategory(MY_ID, cat));
        assertEquals(ErrorCode.CATEGORY_HAS_BILLS.getCode(), e.getCode());
        System.out.println("  3 笔引用 → 被拒(" + e.getCode() + ") ✓");
    }

    // ==================== 顺序：③ 先于 ④ ====================

    @Test
    void childCheckTakesPrecedenceOverBillCheck() {
        System.out.println("\n===== 护栏顺序：有子分类 + 有账单 → 应报 3013 而不是 3014 =====");
        Long parent = newCategory(MY_ID, "父", 0L, "EXPENSE");
        newCategory(MY_ID, "子", parent, "EXPENSE");
        newBill(parent);   // 父分类也被账单引用

        BusinessException e = assertThrows(BusinessException.class,
                () -> categoryService.deleteCategory(MY_ID, parent));

        assertEquals(ErrorCode.CATEGORY_HAS_CHILDREN.getCode(), e.getCode(),
                "★ 应先报「有子分类」，让用户先处理子分类");
        System.out.println("  报 " + e.getCode() + "（有子分类），符合预期顺序 ✓");
    }

    // ==================== 放行路径 ====================

    @Test
    void canDeleteCategoryWithoutBills() {
        System.out.println("\n===== 放行：无账单无子分类 → 可以删 =====");
        Long cat = newCategory(MY_ID, "空分类", 0L, "EXPENSE");

        categoryService.deleteCategory(MY_ID, cat);

        assertEquals(1, deletedFlag(cat), "应是逻辑删除（deleted=1）");
        // 逻辑删除后 selectById 查不到
        assertNull(categoryMapper.selectById(cat), "@TableLogic 会让查询过滤掉它");
        System.out.println("  ✓ 逻辑删除成功（deleted=1），查询已过滤");
    }

    @Test
    void canDeleteAfterBillsAreDeleted() {
        System.out.println("\n===== ★ 放行：账单被删后，分类即可删除 =====");
        Long cat = newCategory(MY_ID, "先有账单", 0L, "EXPENSE");
        Bill bill = newBill(cat);

        // 先确认有账单时拦得住
        assertEquals(ErrorCode.CATEGORY_HAS_BILLS.getCode(),
                assertThrows(BusinessException.class,
                        () -> categoryService.deleteCategory(MY_ID, cat)).getCode());
        System.out.println("  有账单时被拦(3014) ✓");

        // ★ 删掉账单（逻辑删除后 deleted=1）→ 不应再计入"在用"
        billService.deleteBill(MY_ID, bill.getId());

        categoryService.deleteCategory(MY_ID, cat);
        assertEquals(1, deletedFlag(cat));
        System.out.println("  ✓ 账单逻辑删除后，分类可删（说明 count 正确带了 deleted=0）");
    }

    @Test
    void canDeleteChildThenParent() {
        System.out.println("\n===== 放行：先删子再删父（正常清理顺序）=====");
        Long parent = newCategory(MY_ID, "父2", 0L, "EXPENSE");
        Long child = newCategory(MY_ID, "子2", parent, "EXPENSE");

        // 直接删父 → 被拦
        assertEquals(ErrorCode.CATEGORY_HAS_CHILDREN.getCode(),
                assertThrows(BusinessException.class,
                        () -> categoryService.deleteCategory(MY_ID, parent)).getCode());

        categoryService.deleteCategory(MY_ID, child);
        categoryService.deleteCategory(MY_ID, parent);

        assertEquals(1, deletedFlag(child));
        assertEquals(1, deletedFlag(parent));
        System.out.println("  ✓ 先删子再删父，两步都成功");
    }

    @Test
    void deletingCategoryDoesNotTouchBills() {
        System.out.println("\n===== 删分类不改变任何账单数据（仅护栏拦截，不是级联）=====");
        Long cat = newCategory(MY_ID, "待删", 0L, "EXPENSE");
        Bill bill = newBill(cat);

        assertThrows(BusinessException.class, () -> categoryService.deleteCategory(MY_ID, cat));

        // 账单的 category_id 原样保留，金额也没动
        Integer cid = jdbcTemplate.queryForObject(
                "SELECT category_id FROM t_bill WHERE id = ?", Integer.class, bill.getId());
        assertTrue(cid != null && cid.longValue() == cat, "账单的分类引用必须保持不变");
        System.out.println("  ✓ 账单 category_id 仍为 " + cid + "，数据未被触碰");
    }

    // ==================== ★ 第五道护栏：被预算引用（D7 新增）====================

    @Test
    void cannotDeleteCategoryReferencedByBudget() {
        System.out.println("\n===== ★★ 被预算引用的分类不能删 → 3016（D7 新增护栏）=====");
        Long cat = newCategory(MY_ID, "被预算引用", 0L, "EXPENSE");
        // 预算挂在刚建的分类上（注意：此时还没有任何账单，所以 3014 拦不住它）
        Long budgetId = budgetService.create(MY_ID,
                new com.sakuya.notesatwill.entity.DTO.BudgetRequest(
                        bookId, cat, new java.math.BigDecimal("1000.00"), 80)).id();

        BusinessException e = assertThrows(BusinessException.class,
                () -> categoryService.deleteCategory(MY_ID, cat));

        System.out.println("  被拒(" + e.getCode() + ")：" + e.getMessage());
        assertEquals(ErrorCode.CATEGORY_HAS_BUDGET.getCode(), e.getCode());
        assertEquals(0, deletedFlag(cat), "★ 分类不能被删（否则预算会指向不存在的分类）");
        System.out.println("  ✓ 分类保留，预算仍然有效（budgetId=" + budgetId + "）");
    }

    @Test
    void canDeleteCategoryAfterBudgetRemoved() {
        System.out.println("\n===== 放行：删掉预算后，分类即可删除 =====");
        Long cat = newCategory(MY_ID, "先有预算", 0L, "EXPENSE");
        Long budgetId = budgetService.create(MY_ID,
                new com.sakuya.notesatwill.entity.DTO.BudgetRequest(
                        bookId, cat, new java.math.BigDecimal("1000.00"), 80)).id();

        // 有预算 → 被拦
        assertEquals(ErrorCode.CATEGORY_HAS_BUDGET.getCode(),
                assertThrows(BusinessException.class,
                        () -> categoryService.deleteCategory(MY_ID, cat)).getCode());

        // ★ 删掉预算后（逻辑删除）就不该再算"在用"
        budgetService.delete(MY_ID, budgetId);

        categoryService.deleteCategory(MY_ID, cat);
        assertEquals(1, deletedFlag(cat));
        System.out.println("  ✓ 预算删除后，分类可删（说明 count 正确带了 deleted=0）");
    }

    @Test
    void budgetGuardComesAfterBillGuard() {
        System.out.println("\n===== 护栏顺序：既被账单又被预算引用 → 应报 3014（账单优先）=====");
        Long cat = newCategory(MY_ID, "双重引用", 0L, "EXPENSE");
        newBill(cat);
        budgetService.create(MY_ID, new com.sakuya.notesatwill.entity.DTO.BudgetRequest(
                bookId, cat, new java.math.BigDecimal("1000.00"), 80));

        BusinessException e = assertThrows(BusinessException.class,
                () -> categoryService.deleteCategory(MY_ID, cat));

        assertEquals(ErrorCode.CATEGORY_HAS_BILLS.getCode(), e.getCode(),
                "★ 先报「有账单」（3014），因为账单是更主要的使用关系");
        System.out.println("  报 " + e.getCode() + "（有账单），符合预期顺序 ✓");
    }
}
