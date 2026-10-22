package com.sakuya.notesatwill;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Category;
import com.sakuya.notesatwill.entity.DTO.BudgetRequest;
import com.sakuya.notesatwill.entity.DTO.CategoryRequest;
import com.sakuya.notesatwill.entity.VO.BudgetVO;
import com.sakuya.notesatwill.service.BookService;
import com.sakuya.notesatwill.service.BudgetService;
import com.sakuya.notesatwill.service.CategoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预算 CRUD（任务 5.2）回归测试。
 *
 * <p>核心验收点：
 * <ol>
 *   <li><b>账本级</b>（{@code categoryId = null}）与<b>分类级</b>两种都能建、改、删</li>
 *   <li><b>同账本同分类只允许一条</b> —— 只能靠 Service 拦，因为 NULL 让唯一索引失效</li>
 *   <li>修改是<b>整体替换</b>：能把分类级改成账本级（验证 {@code LambdaUpdateWrapper} 生效）</li>
 *   <li>归属校验：别人的账本、别人的分类都进不来</li>
 * </ol>
 *
 * <p><b>★ 预置分类的真实 id（注意是 6 位）</b>：
 * 1001 餐饮（一级）、100102 餐饮/午餐（二级）、1002 交通、100202 交通/打车、2001 职业收入。
 *
 * @author sakuya
 */
@SpringBootTest
class BudgetCrudTest {

    private static final Long MY_ID = 987493001L;
    private static final Long OTHER_ID = 987493002L;

    private static final Long CAT_DINING = 1001L;      // 餐饮（EXPENSE，一级）
    private static final Long CAT_LUNCH = 100102L;     // 餐饮/午餐（EXPENSE，二级）
    private static final Long CAT_SALARY = 2001L;      // 职业收入（INCOME）

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private BookService bookService;

    @Autowired
    private CategoryService categoryService;

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
            jdbcTemplate.update("DELETE FROM t_budget WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_account_flow WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_bill WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_account WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_book WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM t_category WHERE user_id = ?", u);
        }
    }

    private BudgetRequest req(Long catId, String amount, Integer threshold) {
        return new BudgetRequest(bookId, catId, new BigDecimal(amount), threshold);
    }

    // ==================== 建 ====================

    @Test
    void createsBookLevelBudget() {
        System.out.println("\n===== 账本级预算（categoryId = null）=====");

        BudgetVO vo = budgetService.create(MY_ID, req(null, "3000.00", 80));

        assertNotNull(vo.id());
        assertNull(vo.categoryId(), "账本级的 categoryId 必须是 null");
        assertNull(vo.categoryName());
        assertEquals(0, vo.amount().compareTo(new BigDecimal("3000.00")));
        assertEquals(80, vo.alertThreshold());
        System.out.println("  ✓ 建成功，categoryId=null，额度=" + vo.amount());
    }

    @Test
    void createsCategoryLevelBudget() {
        System.out.println("\n===== 分类级预算 =====");

        BudgetVO vo = budgetService.create(MY_ID, req(CAT_DINING, "1500.00", 90));

        assertEquals(CAT_DINING, vo.categoryId());
        assertEquals("餐饮", vo.categoryName(), "名字应补全");
        assertEquals(90, vo.alertThreshold());
        System.out.println("  ✓ 建成功，分类=" + vo.categoryName() + "，阈值=" + vo.alertThreshold());
    }

    @Test
    void defaultAlertThresholdIs80() {
        System.out.println("\n===== 不传阈值 → 默认 80 =====");
        BudgetVO vo = budgetService.create(MY_ID, req(CAT_DINING, "1000.00", null));
        assertEquals(80, vo.alertThreshold());
        System.out.println("  ✓ 阈值=" + vo.alertThreshold());
    }

    // ==================== 唯一性（Service 层拦）====================

    @Test
    void cannotCreateDuplicateBookLevelBudget() {
        System.out.println("\n===== ★ 账本级不允许重复（NULL 让唯一索引失效，只能 Service 拦）=====");
        budgetService.create(MY_ID, req(null, "3000.00", 80));

        BusinessException e = assertThrows(BusinessException.class,
                () -> budgetService.create(MY_ID, req(null, "5000.00", 80)));

        assertEquals(ErrorCode.BUDGET_ALREADY_EXISTS.getCode(), e.getCode());
        assertEquals(1, countBudgets(), "只应有一条");
        System.out.println("  ✓ 被拒(" + e.getCode() + ")，库里仍 1 条");
    }

    @Test
    void cannotCreateDuplicateCategoryLevelBudget() {
        System.out.println("\n===== 分类级不允许重复 =====");
        budgetService.create(MY_ID, req(CAT_DINING, "1000.00", 80));

        BusinessException e = assertThrows(BusinessException.class,
                () -> budgetService.create(MY_ID, req(CAT_DINING, "2000.00", 80)));
        assertEquals(ErrorCode.BUDGET_ALREADY_EXISTS.getCode(), e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")");
    }

    @Test
    void bookLevelAndCategoryLevelCanCoexist() {
        System.out.println("\n===== 账本级 + 分类级可共存（不冲突）=====");

        budgetService.create(MY_ID, req(null, "3000.00", 80));
        budgetService.create(MY_ID, req(CAT_DINING, "1000.00", 80));
        budgetService.create(MY_ID, req(CAT_LUNCH, "500.00", 80));

        assertEquals(3, countBudgets());
        System.out.println("  ✓ 三条共存：账本级 + 餐饮 + 餐饮/午餐");
    }

    @Test
    void differentBooksCanEachHaveSameCategoryBudget() {
        System.out.println("\n===== 不同账本可以各有一个同名分类预算 =====");
        Long book2 = bookService.create(MY_ID,
                new com.sakuya.notesatwill.entity.DTO.BookRequest("第二个账本", null, null, null)).getId();

        budgetService.create(MY_ID, req(CAT_DINING, "1000.00", 80));
        budgetService.create(MY_ID, new BudgetRequest(book2, CAT_DINING, new BigDecimal("2000.00"), 80));

        assertEquals(2, countBudgets());
        System.out.println("  ✓ 两个账本各一条，互不冲突");
    }

    // ==================== 校验 ====================

    @Test
    void cannotBudgetOnIncomeCategory() {
        System.out.println("\n===== ★ 不能给收入分类设预算（否则已用永远是 0）=====");

        BusinessException e = assertThrows(BusinessException.class,
                () -> budgetService.create(MY_ID, req(CAT_SALARY, "1000.00", 80)));

        assertEquals(ErrorCode.CATEGORY_TYPE_MISMATCH.getCode(), e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")：" + e.getMessage());
    }

    @Test
    void cannotCreateForOthersBook() {
        System.out.println("\n===== 越权：不能用别人的账本 =====");
        Long othersBook = bookService.createDefaultBook(OTHER_ID).getId();

        BusinessException e = assertThrows(BusinessException.class,
                () -> budgetService.create(MY_ID,
                        new BudgetRequest(othersBook, null, new BigDecimal("1000.00"), 80)));

        assertEquals(ErrorCode.BOOK_NOT_FOUND.getCode(), e.getCode());
        assertEquals(0, countBudgets());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")");
    }

    @Test
    void cannotBudgetOnOthersCategory() {
        System.out.println("\n===== 越权：不能用别人的自建分类 =====");
        Category othersCat = categoryService.createCategory(OTHER_ID,
                new CategoryRequest(0L, "别人的分类", null, null, "EXPENSE"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> budgetService.create(MY_ID, req(othersCat.getId(), "1000.00", 80)));

        assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")");
    }

    @Test
    void cannotBudgetOnNonexistentCategory() {
        System.out.println("\n===== 不存在的分类 =====");
        BusinessException e = assertThrows(BusinessException.class,
                () -> budgetService.create(MY_ID, req(99999999L, "1000.00", 80)));
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")");
    }

    // ==================== 改 ====================

    @Test
    void updatesAmount() {
        System.out.println("\n===== 修改额度 =====");
        BudgetVO created = budgetService.create(MY_ID, req(CAT_DINING, "1000.00", 80));

        BudgetVO updated = budgetService.update(MY_ID, created.id(), req(CAT_DINING, "2500.00", 70));

        assertEquals(0, updated.amount().compareTo(new BigDecimal("2500.00")));
        assertEquals(70, updated.alertThreshold());
        System.out.println("  ✓ 额度 1000 → " + updated.amount() + "，阈值 → " + updated.alertThreshold());
    }

    @Test
    void updateCanConvertCategoryLevelToBookLevel() {
        System.out.println("\n===== ★★ 分类级 → 账本级（验证 LambdaUpdateWrapper 能写 null）=====");
        BudgetVO created = budgetService.create(MY_ID, req(CAT_DINING, "1000.00", 80));
        assertEquals(CAT_DINING, created.categoryId());

        // 传 categoryId = null，表示"改成账本级"
        BudgetVO updated = budgetService.update(MY_ID, created.id(), req(null, "3000.00", 80));

        assertNull(updated.categoryId(),
                "★ 必须真的写成 null —— 若用 updateById，not_null 策略会跳过，改不回来");
        assertNull(updated.categoryName());

        // 库里也要确认
        Long stored = jdbcTemplate.queryForObject(
                "SELECT category_id FROM t_budget WHERE id = ?", Long.class, created.id());
        assertNull(stored, "★ 数据库里也必须为 NULL");
        System.out.println("  ✓ 分类级成功改为账本级，库中 category_id = null");
    }

    @Test
    void updateCanConvertBookLevelToCategoryLevel() {
        System.out.println("\n===== 账本级 → 分类级 =====");
        BudgetVO created = budgetService.create(MY_ID, req(null, "3000.00", 80));

        BudgetVO updated = budgetService.update(MY_ID, created.id(), req(CAT_LUNCH, "500.00", 80));

        assertEquals(CAT_LUNCH, updated.categoryId());
        // ★ categoryName 是分类【自身名字】，不含父级路径（返回"午餐"而不是"餐饮/午餐"）。
        //   "父/子"那种写法只用在 AI 候选里 —— 那是给模型消歧义用的，不是展示格式。
        //   前端若需要完整路径，用 categoryId 在已加载的分类树里定位即可。
        assertEquals("午餐", updated.categoryName());
        System.out.println("  ✓ 账本级成功改为分类级：" + updated.categoryName()
                + "（id=" + updated.categoryId() + "）");
    }

    @Test
    void updateExcludesSelfFromDuplicateCheck() {
        System.out.println("\n===== 只改额度不该被判重复（排除自己）=====");
        BudgetVO created = budgetService.create(MY_ID, req(CAT_DINING, "1000.00", 80));

        // 分类不变、只改额度
        BudgetVO updated = budgetService.update(MY_ID, created.id(), req(CAT_DINING, "1200.00", 80));

        assertEquals(0, updated.amount().compareTo(new BigDecimal("1200.00")));
        System.out.println("  ✓ 改成功，没有被自己挡住");
    }

    @Test
    void cannotUpdateToDuplicateCategory() {
        System.out.println("\n===== 改成与另一条重复 → 4011 =====");
        budgetService.create(MY_ID, req(CAT_DINING, "1000.00", 80));
        BudgetVO second = budgetService.create(MY_ID, req(CAT_LUNCH, "500.00", 80));

        BusinessException e = assertThrows(BusinessException.class,
                () -> budgetService.update(MY_ID, second.id(), req(CAT_DINING, "500.00", 80)));

        assertEquals(ErrorCode.BUDGET_ALREADY_EXISTS.getCode(), e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")");
    }

    @Test
    void cannotUpdateOthersBudget() {
        System.out.println("\n===== 越权：不能改别人的预算 =====");
        Long othersBook = bookService.createDefaultBook(OTHER_ID).getId();
        BudgetVO others = budgetService.create(OTHER_ID,
                new BudgetRequest(othersBook, null, new BigDecimal("1000.00"), 80));

        BusinessException e = assertThrows(BusinessException.class,
                () -> budgetService.update(MY_ID, others.id(), req(null, "1.00", 80)));

        assertEquals(ErrorCode.BUDGET_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")");
    }

    // ==================== 删 ====================

    @Test
    void deletesBudget() {
        System.out.println("\n===== 删除预算 =====");
        BudgetVO created = budgetService.create(MY_ID, req(CAT_DINING, "1000.00", 80));

        budgetService.delete(MY_ID, created.id());

        assertEquals(0, countBudgets(), "逻辑删除后不应再被查到");
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM t_budget WHERE id = ?", Integer.class, created.id());
        assertEquals(1, deleted, "应是逻辑删除而不是物理删除");
        System.out.println("  ✓ 逻辑删除成功（deleted=1），查询已过滤");
    }

    @Test
    void cannotDeleteOthersBudget() {
        System.out.println("\n===== 越权：不能删别人的预算 =====");
        Long othersBook = bookService.createDefaultBook(OTHER_ID).getId();
        BudgetVO others = budgetService.create(OTHER_ID,
                new BudgetRequest(othersBook, null, new BigDecimal("1000.00"), 80));

        BusinessException e = assertThrows(BusinessException.class,
                () -> budgetService.delete(MY_ID, others.id()));

        assertEquals(ErrorCode.BUDGET_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")");
    }

    @Test
    void canRecreateAfterDelete() {
        System.out.println("\n===== ★ 删除后可以重新建同分类预算（逻辑删除不占唯一位）=====");
        BudgetVO first = budgetService.create(MY_ID, req(CAT_DINING, "1000.00", 80));
        budgetService.delete(MY_ID, first.id());

        BudgetVO second = budgetService.create(MY_ID, req(CAT_DINING, "2000.00", 80));

        assertNotNull(second.id());
        assertEquals(1, countBudgets());
        System.out.println("  ✓ 重建成功（id=" + second.id() + "）");
    }

    // ==================== 列表 ====================

    @Test
    void listFiltersByBook() {
        System.out.println("\n===== 列表按账本筛选 =====");
        Long book2 = bookService.create(MY_ID,
                new com.sakuya.notesatwill.entity.DTO.BookRequest("账本二", null, null, null)).getId();

        budgetService.create(MY_ID, req(null, "3000.00", 80));
        budgetService.create(MY_ID, new BudgetRequest(book2, null, new BigDecimal("1000.00"), 80));

        List<BudgetVO> all = budgetService.list(MY_ID, null);
        List<BudgetVO> onlyBook1 = budgetService.list(MY_ID, bookId);

        assertEquals(2, all.size());
        assertEquals(1, onlyBook1.size());
        assertEquals(bookId, onlyBook1.get(0).bookId());
        assertTrue(all.size() > onlyBook1.size());
        System.out.println("  ✓ 全部 2 条，bookId=" + bookId + " 只有 1 条");
    }

    @Test
    void listCannotReadOthersBook() {
        System.out.println("\n===== 越权：不能按别人的账本查列表 =====");
        Long othersBook = bookService.createDefaultBook(OTHER_ID).getId();

        BusinessException e = assertThrows(BusinessException.class,
                () -> budgetService.list(MY_ID, othersBook));
        assertEquals(ErrorCode.BOOK_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")");
    }

    private int countBudgets() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_budget WHERE user_id = ? AND deleted = 0",
                Integer.class, MY_ID);
        return n == null ? 0 : n;
    }
}
