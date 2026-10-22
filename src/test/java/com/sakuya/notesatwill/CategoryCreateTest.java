package com.sakuya.notesatwill;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Category;
import com.sakuya.notesatwill.entity.DTO.CategoryRequest;
import com.sakuya.notesatwill.entity.VO.CategoryTreeVO;
import com.sakuya.notesatwill.service.CategoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code CategoryService.createCategory} 的回归测试（真连库）。
 *
 * <p>重点覆盖四个分支：
 * <ol>
 *   <li><b>建一级分类（parentId 缺省 / =0）</b> —— 这是修复前会误抛
 *       {@code CATEGORY_TYPE_MISMATCH} 的分支，必须能成功</li>
 *   <li>建二级分类：父分类必须是「预置 或 我自建」，且 type 一致</li>
 *   <li>同级重名被拒</li>
 *   <li>userId 由服务端写死，不接受伪造</li>
 * </ol>
 *
 * @author sakuya
 */
@SpringBootTest
class CategoryCreateTest {

    private static final Long MY_ID = 987640001L;
    private static final Long OTHER_ID = 987640002L;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM t_category WHERE user_id IN (?, ?)", MY_ID, OTHER_ID);
    }

    private void clear() {
        tearDown();
    }

    // ==================== 分支1：建一级分类 ====================

    @Test
    void createRootCategoryWithNullParentId() {
        clear();
        System.out.println("\n===== 建一级分类（parentId = null）=====");

        // parentId 传 null，应被当作 0 处理
        Category created = categoryService.createCategory(MY_ID,
                new CategoryRequest(null, "宠物", "pet", null, "EXPENSE"));

        assertNotNull(created.getId(), "主键应已回填");
        assertEquals(0L, created.getParentId(), "parentId 应被规范成 0");
        assertEquals(MY_ID, created.getUserId(), "userId 必须是当前登录用户");
        System.out.printf("  成功：id=%d, name=%s, parentId=%d, sort=%d%n",
                created.getId(), created.getName(), created.getParentId(), created.getSort());

        // 缺省 sort 应为 0（该 parent 下无兄弟）
        assertEquals(0, created.getSort(), "首个一级分类的 sort 应为 0");
        System.out.println("  ✓ 一级分类创建成功（修复前此处会误抛 TYPE_MISMATCH）");
    }

    @Test
    void createRootCategoryWithZeroParentId() {
        clear();
        System.out.println("\n===== 建一级分类（parentId = 0，显式传）=====");

        Category created = categoryService.createCategory(MY_ID,
                new CategoryRequest(0L, "运动", "sport", 5, "EXPENSE"));

        assertEquals(0L, created.getParentId());
        assertEquals(5, created.getSort(), "显式传的 sort 应生效");
        System.out.println("  ✓ 显式传 0 也可建一级分类，sort=5 生效");
    }

    @Test
    void createRootCategoryForIncomeType() {
        clear();
        System.out.println("\n===== 建 INCOME 一级分类 =====");

        // 验证 type 不是硬编码成 EXPENSE
        Category created = categoryService.createCategory(MY_ID,
                new CategoryRequest(null, "稿费", "pen", null, "INCOME"));

        assertEquals("INCOME", created.getType(), "type 应保留请求值");
        System.out.println("  ✓ INCOME 一级分类创建成功，type=" + created.getType());
    }

    // ==================== 分支2：建二级分类 ====================

    @Test
    void createChildUnderPresetParent() {
        clear();
        System.out.println("\n===== 二级分类挂在系统预置下（餐饮 1001）=====");

        Category created = categoryService.createCategory(MY_ID,
                new CategoryRequest(1001L, "夜宵", "food", null, "EXPENSE"));

        assertEquals(1001L, created.getParentId());
        assertEquals(MY_ID, created.getUserId(), "自建子分类的 userId 应是我");

        // 树里应能查到，且嵌套在餐饮下
        List<CategoryTreeVO> tree = categoryService.tree(MY_ID, "EXPENSE");
        CategoryTreeVO dining = tree.stream().filter(n -> n.id() == 1001L).findFirst().orElseThrow();
        assertTrue(dining.children().stream().anyMatch(c -> "夜宵".equals(c.name())),
                "自建二级应挂在餐饮下");
        System.out.printf("  餐饮下现有 %d 个子分类，含「夜宵」✓%n", dining.children().size());
    }

    @Test
    void createChildUnderOwnCustomParent() {
        clear();
        System.out.println("\n===== 二级分类挂在【自己建的】一级分类下 =====");

        Category parent = categoryService.createCategory(MY_ID,
                new CategoryRequest(null, "宠物", "pet", null, "EXPENSE"));
        Category child = categoryService.createCategory(MY_ID,
                new CategoryRequest(parent.getId(), "猫粮", "cat", null, "EXPENSE"));

        assertEquals(parent.getId(), child.getParentId());
        assertEquals(MY_ID, child.getUserId());
        System.out.println("  ✓ 自建父子关系建立成功");
    }

    @Test
    void cannotCreateChildUnderOthersCategory() {
        clear();
        System.out.println("\n===== 不能挂到【别人的】分类下 =====");

        Category othersParent = categoryService.createCategory(OTHER_ID,
                new CategoryRequest(null, "他人的分类", "x", null, "EXPENSE"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> categoryService.createCategory(MY_ID,
                        new CategoryRequest(othersParent.getId(), "偷挂的", "y", null, "EXPENSE")),
                "不该能挂到别人的分类下");
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  被拒，code=" + e.getCode() + "（期望 3010）✓");
    }

    @Test
    void cannotCreateChildWithMismatchedType() {
        clear();
        System.out.println("\n===== 父子 type 必须一致 =====");

        // 餐饮(1001) 是 EXPENSE，却要挂一个 INCOME 的子分类
        BusinessException e = assertThrows(BusinessException.class,
                () -> categoryService.createCategory(MY_ID,
                        new CategoryRequest(1001L, "错误收入", "x", null, "INCOME")),
                "父子 type 不一致应被拒");
        assertEquals(ErrorCode.CATEGORY_TYPE_MISMATCH.getCode(), e.getCode());
        System.out.println("  被拒，code=" + e.getCode() + "（期望 3015）✓");
    }

    @Test
    void cannotCreateChildUnderNonexistentParent() {
        clear();
        System.out.println("\n===== 父分类不存在 =====");

        BusinessException e = assertThrows(BusinessException.class,
                () -> categoryService.createCategory(MY_ID,
                        new CategoryRequest(99999999L, "孤儿", "x", null, "EXPENSE")));
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  被拒，code=" + e.getCode() + "（期望 3010）✓");
    }

    // ==================== 分支3：重名 ====================

    @Test
    void duplicateNameUnderSameParentIsRejected() {
        clear();
        System.out.println("\n===== 同级重名 =====");

        categoryService.createCategory(MY_ID,
                new CategoryRequest(null, "宠物", "pet", null, "EXPENSE"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> categoryService.createCategory(MY_ID,
                        new CategoryRequest(null, "宠物", "pet2", null, "EXPENSE")),
                "同一个 parent 下同名应被拒");
        assertEquals(ErrorCode.CATEGORY_NAME_DUPLICATED.getCode(), e.getCode());
        System.out.println("  同级重名被拒，code=" + e.getCode() + "（期望 3012）✓");
    }

    @Test
    void sameNameAllowedUnderDifferentParents() {
        clear();
        System.out.println("\n===== 不同父节点下允许同名 =====");

        // 一级「宠物」和 1001(餐饮) 下的「宠物」，parent 不同，应都允许
        Category a = categoryService.createCategory(MY_ID,
                new CategoryRequest(null, "宠物", "pet", null, "EXPENSE"));
        Category b = categoryService.createCategory(MY_ID,
                new CategoryRequest(1001L, "宠物", "pet", null, "EXPENSE"));

        assertNotNull(a.getId());
        assertNotNull(b.getId());
        System.out.println("  ✓ 不同 parent 下同名共存（a=" + a.getId() + ", b=" + b.getId() + "）");
    }

    @Test
    void nameCollidingWithPresetIsAllowed() {
        clear();
        System.out.println("\n===== 与预置同名（当前策略：允许）=====");

        // 预置已有「餐饮」(user_id IS NULL)。自建一个同名的一级分类：
        // 当前实现把"预置名"与"自建名"分开算（带 user_id 条件），所以允许。
        Category mine = categoryService.createCategory(MY_ID,
                new CategoryRequest(null, "餐饮", "myfood", null, "EXPENSE"));

        assertEquals(MY_ID, mine.getUserId());
        System.out.println("  ✓ 允许自建与预置同名的分类（user_id 条件使两者分开计）");

        List<CategoryTreeVO> tree = categoryService.tree(MY_ID, "EXPENSE");
        long sameName = tree.stream().filter(n -> "餐饮".equals(n.name())).count();
        System.out.printf("  树里名为「餐饮」的一级分类有 %d 个（预置1 + 自建1）%n", sameName);
        assertEquals(2, sameName, "应同时存在预置与自建的「餐饮」");
    }

    @Test
    void otherUsersDuplicateDoesNotBlockMe() {
        clear();
        System.out.println("\n===== 别人建过的名字不影响我 =====");

        categoryService.createCategory(OTHER_ID,
                new CategoryRequest(null, "宠物", "pet", null, "EXPENSE"));

        // 我也建同名的，应该成功（重名校验带 user_id 条件）
        Category mine = categoryService.createCategory(MY_ID,
                new CategoryRequest(null, "宠物", "pet", null, "EXPENSE"));
        assertNotNull(mine.getId());
        System.out.println("  ✓ 不与他人重名冲突");
    }

    // ==================== 分支4：userId 写死 + sort ====================

    @Test
    void userIdIsTakenFromLoginNotFromRequest() {
        clear();
        System.out.println("\n===== userId 由服务端写死 =====");

        Category created = categoryService.createCategory(MY_ID,
                new CategoryRequest(null, "安全测试", "x", null, "EXPENSE"));

        // CategoryRequest 里根本没有 userId 字段，所以只能是我 —— 这里断言结果
        assertEquals(MY_ID, created.getUserId(), "userId 必须来自入参 userId");
        assertNotNull(created.getUserId(), "绝不能为 null —— 否则会变成所有人的系统预置分类！");
        System.out.println("  ✓ userId=" + created.getUserId() + "（非 null，未污染为系统预置）");
    }

    @Test
    void sortIsAppendedToEndOfSiblings() {
        clear();
        System.out.println("\n===== sort 缺省时追加到末尾 =====");

        Category a = categoryService.createCategory(MY_ID,
                new CategoryRequest(null, "第一个", "x", null, "EXPENSE"));
        Category b = categoryService.createCategory(MY_ID,
                new CategoryRequest(null, "第二个", "x", null, "EXPENSE"));
        Category c = categoryService.createCategory(MY_ID,
                new CategoryRequest(null, "第三个", "x", null, "EXPENSE"));

        System.out.printf("  三个兄弟的 sort: %d, %d, %d%n", a.getSort(), b.getSort(), c.getSort());
        assertEquals(0, a.getSort());
        assertEquals(1, b.getSort());
        assertEquals(2, c.getSort(), "sort 应递增追加，而非全部落到 0");

        // 树的顺序应按 sort
        List<CategoryTreeVO> tree = categoryService.tree(MY_ID, "EXPENSE");
        List<String> myNames = tree.stream()
                .filter(n -> !n.isPreset())
                .map(CategoryTreeVO::name).toList();
        System.out.println("  树中我的一级分类顺序: " + myNames);
        assertFalse(myNames.isEmpty());
    }

    @Test
    void sortScopeRespectsParent() {
        clear();
        System.out.println("\n===== sort 作用域按 parent 隔离 =====");

        Category root1 = categoryService.createCategory(MY_ID,
                new CategoryRequest(null, "甲", "x", 10, "EXPENSE"));
        // 在 root1 下建子分类，sort 缺省应为 0（该 parent 下无兄弟），
        // 而不是继承 root1 的 sort+1
        Category child = categoryService.createCategory(MY_ID,
                new CategoryRequest(root1.getId(), "甲的子", "x", null, "EXPENSE"));

        System.out.printf("  root1.sort=%d, child.sort=%d%n", root1.getSort(), child.getSort());
        assertEquals(0, child.getSort(), "子分类的 sort 应基于同 parent 下的兄弟计算，与父的 sort 无关");
        System.out.println("  ✓ 作用域按 parentId 隔离");
    }
}
