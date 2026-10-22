package com.sakuya.notesatwill.controller;

import com.sakuya.notesatwill.common.result.Result;
import com.sakuya.notesatwill.entity.Category;
import com.sakuya.notesatwill.entity.DTO.CategoryRequest;
import com.sakuya.notesatwill.entity.VO.CategoryTreeVO;
import com.sakuya.notesatwill.service.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分类接口（二级树）。
 *
 * <p><b>本类所有接口都需要登录</b>，无需在 {@code SecurityConfig} 单独放行：
 * 它们属于 {@code anyRequest().authenticated()}，未登录访问由
 * {@code authenticationEntryPoint} 统一返回 {@code Result} 结构的 401（错误码 2002）。
 *
 * <p><b>★ 身份一律用 {@code @AuthenticationPrincipal Long userId} 取，绝不接受前端传入。</b>
 * 注意分类是「预置共享 + 自建私有」的混合模型：同一个接口返回的数据里，
 * 既有所有用户都能看到的系统预置分类，也有只有本人可见的自建分类。
 * 服务端靠 token 里的 userId 区分这两者 —— 所以 <b>userId 不能由前端指定</b>，
 * 否则用户能看到甚至冒充别人的私有分类。
 *
 * <p><b>统一响应纪律</b>：业务失败一律 HTTP 200 + {@code Result.code}，
 * 因此本类不加任何 {@code @ResponseStatus}；{@code BusinessException} 直接抛出。
 *
 * @author sakuya
 */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * 查询分类树（两级嵌套：系统预置 + 当前用户自建，合并返回）。
     *
     * <p><b>{@code type} 是必填参数</b>，因为收入与支出是两棵<b>完全隔离</b>的树。
     * 不传的话无法确定查哪一棵，所以用 {@code @RequestParam} 强制要求
     * （Spring 在参数缺失时会抛 {@code MissingServletRequestParameterException}，
     * 由全局异常处理器转成错误码 1001，不会出现 500）。
     *
     * <p>返回的是嵌套结构，前端可直接喂给 {@code el-tree} / {@code el-cascader}，
     * 不必自己按 {@code parentId} 拼装（避免"子分类找不到父级被静默丢弃"的 bug）。
     *
     * <p><b>验收口径</b>：
     * <pre>
     *   ?type=EXPENSE → 10 个一级 + 44 个二级 = 54 条
     *   ?type=INCOME  →  4 个一级 + 14 个二级 = 18 条
     * </pre>
     * 数字对不上通常意味着合并查询的 {@code or()} 括号写错了，
     * 导致另一棵树的数据串了进来。
     *
     * @param userId 当前登录用户 id（来自 token）
     * @param type   收支类型：EXPENSE / INCOME
     */
    @GetMapping
    public Result<List<CategoryTreeVO>> tree(@AuthenticationPrincipal Long userId,
                                             @RequestParam String type) {
        return Result.success(categoryService.tree(userId, type));
    }

    /**
     * 新建分类（支持一级与二级）。
     *
     * <p>{@code parentId} 不传或传 null 表示建<b>一级分类</b>，
     * 传父分类 id 则建二级分类。二级分类会校验父分类
     * 是否「存在 + 我能看到 + type 一致」。
     *
     * <p>返回新建的分类实体（含回填 id），前端可直接插入树中，不必整棵重新拉取。
     *
     * @param userId  当前登录用户 id（来自 token）
     * @param request 分类信息；{@code type} 必填
     */
    @PostMapping
    public Result<Category> create(@AuthenticationPrincipal Long userId,
                                   @Valid @RequestBody CategoryRequest request) {
        return Result.success(categoryService.createCategory(userId, request));
    }

    /**
     * 修改分类（只能改名称、图标、排序）。
     *
     * <p><b>注意：{@code type} 与 {@code parentId} 会被服务端忽略</b>，
     * 即使请求体里传了也不会生效。原因是改这两个字段会让该分类下
     * 历史账单的收支属性反转、或让分类挪到另一棵树，属于历史数据错位。
     * 前端做表单时应把这两个字段设为只读。
     *
     * <p>系统预置分类（{@code isPreset = true}）调用本接口会返回 3011，
     * 前端应依据树接口返回的 {@code isPreset} 直接隐藏编辑入口。
     *
     * @param userId  当前登录用户 id（来自 token）
     * @param id      要修改的分类 id
     * @param request 新的分类信息
     */
    @PutMapping("/{id}")
    public Result<Category> update(@AuthenticationPrincipal Long userId,
                                   @PathVariable Long id,
                                   @Valid @RequestBody CategoryRequest request) {
        return Result.success(categoryService.updateCategory(userId, id, request));
    }

    /**
     * 删除分类（逻辑删除）。
     *
     * <p>两条业务规则，前端需要分别提示：
     * <ul>
     *   <li>系统预置分类 → 3011（应通过 {@code isPreset} 提前隐藏入口）</li>
     *   <li>下面还有子分类 → 3013，提示用户先删子分类。
     *       服务端<b>不做级联删除</b>，因为"删一个影响一片"的破坏性操作
     *       必须显式确认，不该默认发生</li>
     * </ul>
     *
     * <p>删除是逻辑删除，历史账单仍能显示该分类名（前端可标注"已删除"）。
     *
     * @param userId 当前登录用户 id（来自 token）
     * @param id     要删除的分类 id
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal Long userId,
                               @PathVariable Long id) {
        categoryService.deleteCategory(userId, id);
        return Result.success();
    }
}
