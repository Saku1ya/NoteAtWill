package com.sakuya.notesatwill;

import com.sakuya.notesatwill.entity.DTO.CategoryRequest;
import com.sakuya.notesatwill.entity.VO.CategoryTreeVO;
import com.sakuya.notesatwill.mapper.CategoryMapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分类树查询的回归测试（真连库）。
 *
 * <p>核心目的：<b>用数字证明 {@code or()} 的括号写对了</b>。
 * <pre>
 *   ?type=EXPENSE → 12 个一级 + 14 个二级 = 26
 *   ?type=INCOME  →  4 个一级 + 0 个二级 = 4（收入侧扁平）
 * </pre>
 * 若括号写错（OR 吞掉 type 条件），EXPENSE 会查出超过 26 条 —— 收入分类串了进来。
 *
 * <p>另外验证：预置标记、自建分类合并、children 非 null、排序。
 *
 * @author sakuya
 */
@SpringBootTest
class CategoryTreeQueryTest {

    private static final Long TEST_USER = 987650001L;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        // 物理删除测试用户自建的分类（不走 @TableLogic，避免残留累积）
        jdbcTemplate.update("DELETE FROM t_category WHERE user_id = ?", TEST_USER);
    }

    /** 递归统计树里所有节点数（含各级）。 */
    private int countNodes(List<CategoryTreeVO> nodes) {
        int n = 0;
        for (CategoryTreeVO node : nodes) {
            n += 1 + countNodes(node.children());
        }
        return n;
    }

    @Test
    void expenseTreeHas26NodesAnd12Roots() {
        List<CategoryTreeVO> tree = categoryService.tree(TEST_USER, "EXPENSE");

        System.out.println("\n===== 支出树 =====");
        System.out.printf("  一级分类 %d 个，总节点 %d 个%n", tree.size(), countNodes(tree));
        tree.stream().limit(3).forEach(n ->
                System.out.printf("    %s(sort=%d, preset=%s) -> %d 个子分类%n",
                        n.name(), n.sort(), n.isPreset(), n.children().size()));

        // ★ 括号正确性的核心断言
        assertEquals(12, tree.size(), "支出树应有 12 个一级分类（零食、饮品已提为一级）");
        assertEquals(26, countNodes(tree), "★ 支出树应共 26 个节点 —— 多于 26 说明 or() 括号写错，收入分类串进来了");

        // 预置标记：种子数据 user_id 全为 NULL，所以全部 isPreset = true
        assertTrue(tree.stream().allMatch(CategoryTreeVO::isPreset), "预置分类应全部标记 isPreset");

        // children 必须是空列表而不是 null（前端要能直接 v-for）
        CategoryTreeVO first = tree.get(0);
        assertNotNull(first.children(), "children 不应为 null");

        // 排序：餐饮(1) 在最前
        assertEquals("餐饮", first.name(), "sort=1 的「餐饮」应排第一");
        assertEquals(5, first.children().size(), "餐饮下应有 5 个二级分类（零食、饮品已提为一级）");
        System.out.println("  ✓ 26 = 12 + 14，未串树");
    }

    @Test
    void incomeTreeHas4NodesAnd4Roots() {
        List<CategoryTreeVO> tree = categoryService.tree(TEST_USER, "INCOME");

        System.out.println("\n===== 收入树 =====");
        System.out.printf("  一级分类 %d 个，总节点 %d 个%n", tree.size(), countNodes(tree));
        tree.forEach(n -> System.out.printf("    %s(sort=%d) -> %d 个子分类%n",
                n.name(), n.sort(), n.children().size()));

        assertEquals(4, tree.size(), "收入树应有 4 个一级分类");
        assertEquals(4, countNodes(tree), "★ 收入树应共 4 个节点（全部扁平，无二级）");
        System.out.println("  ✓ 4 = 4 + 0");
    }

    @Test
    void twoTreesAreIsolated() {
        System.out.println("\n===== 两棵树隔离性 =====");

        List<CategoryTreeVO> expense = categoryService.tree(TEST_USER, "EXPENSE");
        List<CategoryTreeVO> income = categoryService.tree(TEST_USER, "INCOME");

        // 支出树里不能出现任何 INCOME 类型的节点（这正是 or() 括号写错时的症状）
        assertTrue(expense.stream().allMatch(n -> "EXPENSE".equals(n.type())),
                "支出树中混入了非 EXPENSE 节点 —— or() 括号有问题");
        assertTrue(income.stream().allMatch(n -> "INCOME".equals(n.type())),
                "收入树中混入了非 INCOME 节点");

        // 两棵树的 id 不应有交集
        List<Long> eIds = expense.stream().map(CategoryTreeVO::id).toList();
        List<Long> iIds = income.stream().map(CategoryTreeVO::id).toList();
        assertTrue(eIds.stream().noneMatch(iIds::contains), "两棵树不应有重叠的分类 id");
        System.out.println("  ✓ 支出树 " + expense.size() + " 个根 / 收入树 " + income.size() + " 个根，完全隔离");
    }

    @Test
    void userCustomCategoriesAreMergedIn() {
        System.out.println("\n===== 预置 + 自建 合并 =====");

        // 直接插一条该用户的自建一级分类（绕过 Service，本测试只关心查询）
        jdbcTemplate.update(
                "INSERT INTO t_category (user_id, parent_id, name, icon, type, sort, deleted, create_time, update_time) "
                        + "VALUES (?, 0, ?, 'pet', 'EXPENSE', 99, 0, NOW(), NOW())",
                TEST_USER, "宠物");

        List<CategoryTreeVO> tree = categoryService.tree(TEST_USER, "EXPENSE");
        System.out.printf("  合并后一级分类 %d 个，总节点 %d 个%n", tree.size(), countNodes(tree));

        // 自建分类应出现在树里
        assertTrue(tree.stream().anyMatch(n -> "宠物".equals(n.name())),
                "用户自建分类应合并进树");
        // 且标记为非预置
        CategoryTreeVO pet = tree.stream().filter(n -> "宠物".equals(n.name())).findFirst().orElseThrow();
        assertFalse(pet.isPreset(), "自建分类 isPreset 应为 false");

        // sort=99 排最后
        assertEquals("宠物", tree.get(tree.size() - 1).name(), "sort=99 应排在最后");
        // 总数 = 26 + 1
        assertEquals(27, countNodes(tree), "总节点应为 27（26 预置 + 1 自建）");

        // 换个用户查，看不到「宠物」
        List<CategoryTreeVO> otherUser = categoryService.tree(TEST_USER + 1, "EXPENSE");
        assertTrue(otherUser.stream().noneMatch(n -> "宠物".equals(n.name())),
                "别人的自建分类不应被看到");
        assertEquals(26, countNodes(otherUser), "其他用户仍应只看到 26 条预置");
        System.out.println("  ✓ 自建分类本人可见(27)、他人不可见(26)");
    }

    @Test
    void customChildIsNestedUnderItsPresetParent() {
        System.out.println("\n===== 自建二级挂到预置一级下 =====");

        // 在预置的「餐饮」(1001) 下加一个自建二级分类
        jdbcTemplate.update(
                "INSERT INTO t_category (user_id, parent_id, name, icon, type, sort, deleted, create_time, update_time) "
                        + "VALUES (?, 1001, ?, 'food', 'EXPENSE', 99, 0, NOW(), NOW())",
                TEST_USER, "夜宵");

        List<CategoryTreeVO> tree = categoryService.tree(TEST_USER, "EXPENSE");
        CategoryTreeVO dining = tree.stream().filter(n -> n.id() == 1001L).findFirst().orElseThrow();

        System.out.printf("  餐饮下有 %d 个子分类%n", dining.children().size());
        assertTrue(dining.children().stream().anyMatch(c -> "夜宵".equals(c.name())),
                "自建二级分类应挂在预置的餐饮下");
        assertEquals(6, dining.children().size(), "餐饮下应为 5 预置 + 1 自建 = 6（零食/饮品已提为一级）");
        System.out.println("  ✓ 自建二级正确嵌套（7 + 1 = 8）");
    }

    @Test
    void emptyTreeIsNotAnError() {
        System.out.println("\n===== 空树（无任何该类型分类）=====");

        // 用一个不存在于数据库的 type 值，模拟"查不到"
        // 注意：这里不校验 type 合法性（那是 Controller 的 @Pattern 负责的）
        List<CategoryTreeVO> tree = categoryService.tree(TEST_USER, "NONEXISTENT");

        assertNotNull(tree, "不应返回 null");
        assertTrue(tree.isEmpty(), "查不到时应返回空列表而不是抛异常");
        System.out.println("  ✓ 返回空列表，未抛异常");
    }
}
