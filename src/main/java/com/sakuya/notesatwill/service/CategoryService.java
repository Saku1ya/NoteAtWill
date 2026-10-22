package com.sakuya.notesatwill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Bill;
import com.sakuya.notesatwill.entity.Budget;
import com.sakuya.notesatwill.entity.Category;
import com.sakuya.notesatwill.entity.DTO.CategoryRequest;
import com.sakuya.notesatwill.entity.VO.CategoryTreeVO;
import com.sakuya.notesatwill.mapper.BillMapper;
import com.sakuya.notesatwill.mapper.BudgetMapper;
import com.sakuya.notesatwill.mapper.CategoryMapper;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryMapper categoryMapper;
    private final BillMapper billMapper;
    private final BudgetMapper budgetMapper;


    /**
     * 查询分类树（两级嵌套，系统预置 + 用户自建合并返回）。
     *
     * <p><b>实现分四步</b>：
     * <ol>
     *   <li><b>一次查询</b>取出该 type 下所有候选（一级 + 二级混在一起），
     *       条件是「系统预置（{@code user_id IS NULL}）或 我自建的」</li>
     *   <li>把每个 {@link Category} 转成 {@link CategoryTreeVO}，算出 {@code isPreset}</li>
     *   <li>按 {@code parentId} 分组，得到「父 id → 它的孩子列表」的映射</li>
     *   <li>遍历一级分类，把对应 children 挂上去</li>
     * </ol>
     *
     * <p><b>★ 为什么不逐个父分类去查子分类（N+1）</b>：
     * 那样 10 个一级分类就要 11 次数据库往返。本业务的树<b>只有两级</b>
     * （已实测：无三级、无孤儿节点），所以一次全查 + 内存分组是完全等价的，
     * 而往返次数从 11 降到 1。
     *
     * <p><b>★ 合并查询的括号必须自己写，MyBatis-Plus 不会帮你加</b>：
     * <pre>
     *   // ❌ 生成的 SQL：WHERE type='EXPENSE' AND user_id IS NULL OR user_id = 5
     *   //    AND 优先级高于 OR，实际等于 (type AND NULL) OR (user_id=5)
     *   //    → user_id=5 的行无论 type 是什么都被选中，收入分类会串进支出树
     *   .eq(Category::getType, type).isNull(Category::getUserId).or().eq(Category::getUserId, userId)
     *
     *   // ✅ 生成的 SQL：WHERE type='EXPENSE' AND (user_id IS NULL OR user_id = 5)
     *   //    用 .and(w -&gt; ...) 把 OR 包起来，才能形成括号
     *   .eq(Category::getType, type).and(w -&gt; w.isNull(Category::getUserId).or().eq(Category::getUserId, userId))
     * </pre>
     * MP 只会在整个 wrapper 外面套一对括号，那对括号<b>不改变内部的运算优先级</b>。
     *
     * <p><b>★ 为什么用 {@code isNull} 而不是 {@code eq(..., null)}</b>：
     * 系统预置的判定条件是 {@code user_id IS NULL}（实测 72 条种子数据全是 NULL），
     * 而 {@code eq(field, null)} 生成的是 {@code user_id = NULL} ——
     * SQL 里 {@code = NULL} 恒为 UNKNOWN（不成立），一行都查不到。
     * 两者语义完全不同，写错的表现是"系统预置分类全都不见了"。
     *
     * <p><b>排序为什么放在 SQL 里而不是内存里</b>：{@code ORDER BY sort, id}
     * 同时作用于两级 —— 一级分类之间有序，<b>每个父分类下的二级也各自有序</b>
     * （分组时会保持原顺序）。这样第 3、4 步都不需要再排序。
     * {@code id ASC} 这个 tie-breaker 不能省：{@code sort} 无唯一约束，
     * 并列时 MySQL 不保证顺序稳定，前端会出现"刷新一次换个位置"。
     *
     * @param userId 当前登录用户 id（来自 token），用于合并出该用户自建的分类
     * @param type   收支类型 EXPENSE / INCOME。<b>必须传</b>，
     *               因为收入与支出是两棵完全隔离的树，不加这个条件会把两者混在一起
     * @return 一级分类列表（各自带 children）；没有任何分类时返回空列表
     */
    public List<CategoryTreeVO> tree(Long userId, String type) {
        // ---------- 第 1 步：一次查出该 type 下所有候选（一级+二级） ----------
        List<Category> all = categoryMapper.selectList(
                new LambdaQueryWrapper<Category>()
                        .eq(Category::getType, type)
                        // ★ 括号必须靠 .and(...) 自己加，否则 OR 会吞掉上面的 type 条件
                        .and(w -> w.isNull(Category::getUserId).or().eq(Category::getUserId, userId))
                        .orderByAsc(Category::getSort)
                        .orderByAsc(Category::getId)
        );

        if (all.isEmpty()) {
            // 空树不是异常，返回空列表即可（前端渲染"暂无分类"）
            return List.of();
        }

        // ---------- 第 2 步：实体 -> VO ----------
        // children 先给空列表：record 不可变，一级节点的真实 children 要在第 4 步重新构造
        List<CategoryTreeVO> flat = new ArrayList<>(all.size());
        for (Category c : all) {
            flat.add(new CategoryTreeVO(
                    c.getId(),
                    c.getParentId(),
                    c.getName(),
                    c.getIcon(),
                    c.getType(),
                    c.getSort(),
                    // 系统预置的判定：user_id 为 NULL（不是 user_id = 0）
                    c.getUserId() == null,
                    List.of()
            ));
        }

        // ---------- 第 3 步：按 parentId 分组 ----------
        // 排除 parentId == 0 的节点：它们是一级分类，不是任何人的孩子
        // 找出所有子节点，通过Map<parentId，分类>收集
        Map<Long, List<CategoryTreeVO>> childrenByParent = flat.stream()
                .filter(vo -> vo.parentId() != null && vo.parentId() != 0L)
                .collect(Collectors.groupingBy(CategoryTreeVO::parentId));

        // ---------- 第 4 步：组装两级树 ----------
        //将一二级节点组装起来
        List<CategoryTreeVO> tree = new ArrayList<>();
        for (CategoryTreeVO node : flat) {
            // 只处理一级分类；二级分类会在下面被挂到父节点上，不作为顶层返回
            if (node.parentId() == null || node.parentId() == 0L) {
                List<CategoryTreeVO> children = childrenByParent.getOrDefault(node.id(), List.of());
                // record 不可变，用真实 children 重新构造一个父节点
                tree.add(new CategoryTreeVO(
                        node.id(),
                        node.parentId(),
                        node.name(),
                        node.icon(),
                        node.type(),
                        node.sort(),
                        node.isPreset(),
                        children
                ));
            }
        }

        return tree;
    }

    /**
     * 按 id 查询「当前用户可见」的分类，并校验可见性。
     *
     * <p><b>可见性判定与 {@link #tree} 完全一致</b>：
     * {@code user_id IS NULL}（系统预置，所有人共享）
     * <b>或</b> {@code user_id = 当前用户}（自己自建的）。
     * 抽成公开方法是为了让账单模块复用同一套规则 ——
     * 否则 {@code BillService} 会再写一遍这个条件，
     * 两处一旦不一致，就会出现"我在树上能看到这个分类，
     * 但记账时说分类不存在"这种自相矛盾的现象。
     *
     * <p><b>★ 括号必须自己加</b>：MyBatis-Plus 的 {@code or()} 是原样拼 SQL，
     * 不加括号会生成
     * {@code WHERE id = ? AND user_id IS NULL OR user_id = ?}，
     * 而 AND 优先级高于 OR，实际等于
     * {@code (id = ? AND user_id IS NULL) OR (user_id = ?)} ——
     * 后半段与 id 无关，会把该用户的<b>所有</b>分类都查出来。
     *
     * <p><b>为什么不区分「不存在」与「不属于你」</b>：合并成同一个错误码，
     * 避免被用来枚举分类 id。
     *
     * @param userId     当前登录用户 id（来自 token）
     * @param categoryId 分类 id
     * @return 该用户可见的分类实体
     * @throws BusinessException 分类不存在或对当前用户不可见（{@code CATEGORY_NOT_FOUND}）
     */
    public Category getVisible(Long userId, Long categoryId) {
        if (userId == null || categoryId == null) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }

        Category category = categoryMapper.selectOne(
                new LambdaQueryWrapper<Category>()
                        .eq(Category::getId, categoryId)
                        .and(w -> w.isNull(Category::getUserId)
                                .or().eq(Category::getUserId, userId))
        );
        if (category == null) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        return category;
    }

    /**
     * 批量取分类名：{@code id → name}。
     *
     * <p><b>★ 为什么需要批量版本</b>：预算列表有 N 条，逐条 {@code getVisible} 就是 N 次查询。
     * 一次 {@code selectBatchIds} 就能拿全 —— 列表接口的查询次数不该随数据量增长。
     *
     * <p><b>★ 为什么"查不到就缺席"而不是抛异常</b>：
     * 这是<b>补名字</b>用的，不是校验。分类可能已被逻辑删除
     * （预算创建后分类被删的情形），此时调用方拿到 {@code null} 显示"已删除"即可，
     * 不该因为一条补名字失败就让整个列表接口 500。
     * <b>校验用 {@code getVisible}，补名字用本方法 —— 两者职责不同。</b>
     *
     * <p>{@code selectBatchIds} 会由 {@code @TableLogic} 自动过滤已删除行。
     *
     * @param categoryIds 分类 id 集合（可为空、可含 null —— 内部会过滤）
     * @return id → 分类名；不存在的 id 不会出现在结果里
     */
    public Map<Long, String> nameMap(Collection<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = categoryIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }

        List<Category> list = categoryMapper.selectBatchIds(ids);
        Map<Long, String> map = new HashMap<>(list.size());
        for (Category c : list) {
            map.put(c.getId(), c.getName());
        }
        return map;
    }

    /**
     * 取某分类的<b>子树 id 集合</b>：它自己 + 它的所有直接子分类。
     *
     * <p><b>★ 为什么需要这个方法</b>：
     * 分类级预算挂在「餐饮」（一级）时，用户实际记账选的是「餐饮/午餐」（二级）。
     * 如果预算只统计 {@code category_id = 餐饮的id}，那么它<b>永远是 0 使用量</b> ——
     * 因为没有任何账单会直接挂在「餐饮」上（除非用户记到一级）。
     * 所以预算的"已用"必须按<b>子树</b>统计。
     *
     * <p><b>★ 为什么只取"直接"子分类、不递归</b>：
     * 本项目的分类树<b>固定两级</b>（{@code tree()} 只挂 {@code parentId == 0} 到根，
     * 二级不再有下级）。所以"自己 + 直接子"就是完整子树。
     * 若将来允许三级，这里必须改成递归 —— <b>树的深度变化时记得回来改这里</b>。
     *
     * <p><b>★ 用 selectList + LambdaQueryWrapper 而不是手写 SQL</b>：
     * 这样 {@code @TableLogic} 会自动追加 {@code deleted = 0}，
     * 已删除的子分类不会计入（否则会算进一个已经看不到的分类的支出）。
     *
     * @param userId     当前登录用户 id（用于归属校验）
     * @param categoryId 分类 id
     * @return 自己 + 直接子分类的 id 集合（至少含 {@code categoryId} 本身）
     * @throws BusinessException 分类不存在或不可见（3010）
     */
    public Set<Long> subtreeIds(Long userId, Long categoryId) {
        Category self = getVisible(userId, categoryId);

        Set<Long> ids = new HashSet<>();
        ids.add(self.getId());

        List<Category> children = categoryMapper.selectList(
                new LambdaQueryWrapper<Category>()
                        .eq(Category::getParentId, self.getId())
        );
        for (Category child : children) {
            ids.add(child.getId());
        }
        return ids;
    }

    /**
     * 新建分类（支持一级与二级）。
     *
     * <p><b>★ 关键约束一：{@code userId} 必须是当前登录用户，绝不能由前端传入。</b>
     * 若允许前端指定 {@code userId}，攻击者传 {@code null} 就能建出一个
     * <b>"系统预置分类"</b>（所有用户共享、且他自己也改不掉）—— 这是数据污染，
     * 比越权更难清理，因为受影响的是所有人的分类树。
     *
     * <p><b>★ 关键约束二：二级分类的父分类必须"我能看到"。</b>
     * 判定条件与 {@link #tree} 相同：{@code user_id IS NULL OR user_id = 我}。
     * 少了这一步，用户就能往<b>别人的</b>分类树下挂节点。
     *
     * <p><b>★ 关键约束三：父子的 {@code type} 必须一致。</b>
     * 否则 {@code tree("EXPENSE")} 查出的子分类里会混进 INCOME 节点，
     * <b>等于绕过了"收入与支出是两棵隔离的树"这个设计</b>。
     * 不一致时抛 {@link ErrorCode #CATEGORY_TYPE_MISMATCH}。
     *
     * <p><b>重名校验的作用域是「同一个 user_id + 同一个 parent_id 内」</b>，
     * 所以「餐饮」(parent=0) 与「早餐」(parent=1001) 允许同名。
     * 注意<b>不能靠唯一索引兜底</b>：{@code user_id} 含 NULL，
     * MySQL 唯一索引把多行 NULL 视为互不相等，挡不住预置分类的重名；
     * 且逻辑删除会让"删除后重建"误报冲突。所以只能在 Service 层校验。
     *
     * <p><b>{@code parentId} 缺省处理</b>：不传或传 null 一律按 {@code 0}（一级分类）处理，
     * 前端建一级分类时不必显式传这个字段。
     *
     * @param userId  当前登录用户 id（来自 token）
     * @param request 分类信息；{@code type} 必填，{@code parentId} 缺省为 0
     * @return 已回填 id 的分类实体
     * @throws BusinessException 父分类不存在/不属于我（3010）；父子 type 不一致（3015）；
     *                           同级重名（3012）
     */
    public Category createCategory(Long userId, CategoryRequest request) {
        //parentId 缺省即一级分类（前端建一级分类时不必显式传 0）
        Long parentId = request.parentId() == null ? 0L : request.parentId();

        if (parentId != 0L) {
            // 复用 getVisible：可见性规则
            // 方法内部已经进行错误处理，如不存在和不属于
            // 故在此不做校验
            Category parent = getVisible(userId, parentId);

            // 父子的收支类型必须一致，否则 tree("EXPENSE") 会查出 INCOME 的子节点，
            if (!Objects.equals(parent.getType(), request.type())) {
                throw new BusinessException(ErrorCode.CATEGORY_TYPE_MISMATCH);
            }
        }

        // 同级重名校验：作用域是「同一个 type + 同一个 parentId 内」
        // 个人构建和系统预置不冲突
        // 这样用户可以在预置「餐饮」之外自建一个同名的自己的分类。
        boolean duplicated = categoryMapper.exists(
                new LambdaQueryWrapper<Category>()
                        .eq(Category::getType, request.type())
                        .eq(Category::getParentId, parentId)
                        .eq(Category::getName, request.name())
                        // 自建名只和自建名比；预置名只和预置名比
                        .eq(Category::getUserId, userId)
        );
        if (duplicated) {
            throw new BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATED);
        }

        // ④ 组装并插入
        Category category = new Category();
        // ★ userId 写死成当前登录用户，绝不用 request 里的任何值。
        //   若允许前端指定，攻击者传 null 就能建出一条"系统预置分类"
        //   （所有用户共享、且他自己也改不掉）—— 这是数据污染，比越权更难清理。
        category.setUserId(userId);
        category.setParentId(parentId);
        category.setName(request.name());
        category.setIcon(request.icon());
        category.setType(request.type());
        // sort 缺省时追加到该父节点下的末尾
        category.setSort(request.sort() != null ? request.sort() : nextSort(parentId, request.type(), userId));

        categoryMapper.insert(category);

        // insert 后 id 已由 MP 回填，直接返回，不必再查一次
        return category;
    }

    /**
     * 计算同一父节点下的下一个排序值 = 当前最大 sort + 1。
     *
     * <p>作用域必须同时限定 {@code parentId + type + user_id}：
     * <ul>
     *   <li>{@code parentId} —— 排序只在同一层内比较，
     *       「早餐」的 sort 和「餐饮」的 sort 互不相关</li>
     *   <li>{@code type} —— 收入与支出是两棵隔离的树</li>
     *   <li>{@code user_id} —— 只看自己建的，否则会拿到预置分类的 sort，
     *       使新分类的 sort 跳到一个很大的值</li>
     * </ul>
     *
     * <p>空集合时返回 0（{@code orElse(-1) + 1}）。
     */
    private int nextSort(Long parentId, String type, Long userId) {
        List<Category> siblings = categoryMapper.selectList(
                new LambdaQueryWrapper<Category>()
                        .eq(Category::getParentId, parentId)
                        .eq(Category::getType, type)
                        .eq(Category::getUserId, userId)
        );
        return siblings.stream()
                .map(Category::getSort)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(-1) + 1;
    }

    /**
     * 修改分类（只能改名称、图标、排序）。
     *
     * <p><b>★★ 绝不能修改 {@code type} 与 {@code parentId}，这是本方法最重要的一条。</b>
     * <table border="1">
     *   <caption>改这两个字段的后果</caption>
     *   <tr><th>字段</th><th>改了会怎样</th></tr>
     *   <tr>
     *     <td>{@code type}</td>
     *     <td>该分类下<b>所有历史账单</b>的收支属性突然反转 ——
     *         一笔 25 元支出变成 25 元收入，月度统计、预算执行、图表全部失真</td>
     *   </tr>
     *   <tr>
     *     <td>{@code parentId}</td>
     *     <td>相当于把分类挪到另一棵树或另一个父节点，同样是历史数据错位</td>
     *   </tr>
     * </table>
     * 这两个字段出现在 {@link CategoryRequest} 里是给 {@code create} 用的，
     * 本方法必须<b>显式忽略</b>它们。
     *
     * <p><b>系统预置分类（{@code user_id IS NULL}）不可修改</b>，
     * 抛 {@link ErrorCode#CATEGORY_PRESET_READONLY}。
     * 理由：所有用户共享同一条预置记录，一个人改了，所有人的分类树都跟着变。
     *
     * <p><b>重名校验必须排除自己</b>：加 {@code .ne(Category::getId, id)}，
     * 否则"只改图标不改名"也会命中重名判断，用户会觉得莫名其妙。
     *
     * <p><b>归属校验沿用"不区分不存在与不属于你"的策略</b>，统一抛 3010，防 id 枚举。
     *
     * <p><b>别忘了 setId</b>：{@code updateById} 是按主键更新的，
     * 漏了它生成的 SQL 是 {@code WHERE id = NULL}，匹配不到任何行 ——
     * 接口返回 200 但库一个字没改，属于静默失败（{@code BookService.update} 踩过这个坑）。
     *
     * @param userId  当前登录用户 id（来自 token）
     * @param id      要修改的分类 id
     * @param request 新的分类信息；{@code type} / {@code parentId} 会被忽略
     * @return 修改后的分类实体
     * @throws BusinessException 分类不存在/不属于我（3010）；是系统预置（3011）；同级重名（3012）
     */
    public Category updateCategory(Long userId, Long id, CategoryRequest request) {
        // ① 查出分类。用 selectById 而非按 userId 过滤，
        //    因为下面要区分"不存在/不是我的"和"是系统预置"两种拒绝原因，
        //    需要先拿到实体才能判断 user_id 是否为 null。
        Category existing = categoryMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }

        // ② 系统预置不可改：所有用户共享同一条记录，
        //    一个人改了，所有人的分类树都跟着变。
        if (existing.getUserId() == null) {
            throw new BusinessException(ErrorCode.CATEGORY_PRESET_READONLY);
        }

        // ③ 归属校验：不是自己的就当作不存在（不区分，防 id 枚举）
        if (!Objects.equals(existing.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }

        // ④ 重名校验：改完后不能与该父节点下的兄弟重名。
        //    ★ 必须 .ne(Category::getId, id) 排除自己，
        //      否则"只改图标不改名"也会命中重名判断。
        //    作用域沿用 create 的口径：同一 user + 同一 parent + 同一 type。
        //    注意排除自己后再比，相当于"除了我之外还有没有同名的"。
        boolean duplicated = categoryMapper.exists(
                new LambdaQueryWrapper<Category>()
                        .eq(Category::getType, existing.getType())
                        .eq(Category::getParentId, existing.getParentId())
                        .eq(Category::getName, request.name())
                        .eq(Category::getUserId, userId)
                        .ne(Category::getId, id)
        );
        if (duplicated) {
            throw new BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATED);
        }

        // ⑤ 只 set 允许修改的字段。
        //    ★★ 刻意不 set type、不 set parentId：
        //      · 改 type → 该分类下所有历史账单的收支属性突然反转，
        //        月度统计、预算执行、图表全部失真
        //      · 改 parentId → 相当于把分类挪到另一棵树，同样是历史数据错位
        //    这两个字段出现在 CategoryRequest 里是给 create 用的，本方法必须忽略。
        Category update = new Category();
        // ★ 漏了这行会静默失败：updateById 按主键更新，
        //   id 为 null 时 SQL 变成 WHERE id = NULL，匹配不到任何行，
        //   接口照样返回 200 但库一个字没改（BookService.update 踩过同样的坑）。
        update.setId(id);
        update.setName(request.name());
        update.setIcon(request.icon());
        // sort 仅在显式传入时才改，否则保持原值不动
        if (request.sort() != null) {
            update.setSort(request.sort());
        }

        categoryMapper.updateById(update);

        // ⑥ 重新查一次返回：拿到 DB 里的最新状态（含 update_time），
        //    也顺带验证了更新确实生效
        return categoryMapper.selectById(id);
    }

    /**
     * 删除分类（逻辑删除）。
     *
     * <p><b>★ 为什么必须是逻辑删除而不是物理删除</b>：
     * 历史账单里存的是 {@code category_id}。若把分类物理删掉，
     * 那些账单就变成了"孤儿引用"，前端只能显示空白或 {@code null}，
     * 而用户明明记得当时选过分类 —— 历史数据不可追溯。
     * 所以 {@code @TableLogic} 会把它转成 {@code UPDATE deleted = 1}，
     * 账单查询仍能 JOIN 出分类名（展示时可标注"已删除"）。
     *
     * <p><b>★ 有子分类时必须拒绝（{@link ErrorCode#CATEGORY_HAS_CHILDREN}）</b>，
     * 不能用级联删除。理由：删了父分类而子分类还在，子分类会变成<b>孤儿节点</b> ——
     * {@link #tree} 第 4 步只挂 {@code parentId == 0} 的节点，
     * 这些孤儿的 {@code parentId} 指向一个已删除的父亲，
     * 于是<b>永远查不出来，但数据还在库里，且被账单引用着</b>。
     *
     * <p>那为什么不级联删？因为级联是<b>破坏性操作</b>：
     * 用户点一次"删一级分类"，会连带删掉它下面 7 个二级分类。
     * 这种"删一个影响一片"的行为必须显式确认，不该默认发生。
     * 让用户先删子分类，是把破坏范围控制在明面上。
     *
     * <p><b>★ 该分类下还有账单时必须拒绝（{@link ErrorCode#CATEGORY_HAS_BILLS}）</b>。
     * 若允许删除，历史账单的 {@code category_id} 会指向一条已逻辑删除的分类：
     * <ul>
     *   <li>账单列表补分类名时查不到 —— 界面显示"分类已删除"</li>
     *   <li>按分类聚合统计时出现一个没有名字的空洞</li>
     *   <li>数据本身没坏，但用户看到的是"统计加起来对不上"</li>
     * </ul>
     * 正确做法是让用户先把这些账单改到别的分类，或保留该分类。
     *
     * <p>⚠ <b>并发限制（已知，接受）</b>：本检查是"先查后删"，不是数据库约束。
     * 若在 count 与 delete 之间有新账单写入，仍可能产生一条指向已删分类的账单。
     * 要彻底杜绝需要给分类行加 {@code SELECT ... FOR UPDATE} 并让记账路径也参与锁，
     * 成本远高于收益 —— 这个竞态窗口极窄，且后果只是显示问题，不影响金额与余额。
     *
     * @param userId 当前登录用户 id（来自 token）
     * @param id     要删除的分类 id
     * @throws BusinessException 分类不存在/不属于我（3010）；是系统预置（3011）；
     *                           还有子分类（3013）；还有账单在用（3014）
     */
    @Transactional
    public void deleteCategory(Long userId, Long id) {
        // ① 先查出来，才能判断 user_id 是 null（系统预置）还是有值（自建）
        Category existing = categoryMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }

        // ② 系统预置不可删：它是所有用户共享的数据
        if (existing.getUserId() == null) {
            throw new BusinessException(ErrorCode.CATEGORY_PRESET_READONLY);
        }

        // ③ 归属校验：不是自己的当作不存在（防 id 枚举）
        if (!Objects.equals(existing.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }

        // ④ 有子分类时拒绝删除（不做级联）。
        //    selectCount 会自动带上 deleted = 0（@TableLogic），
        //    所以已被逻辑删除的子分类不计入。
        Long childCount = categoryMapper.selectCount(
                new LambdaQueryWrapper<Category>()
                        .eq(Category::getParentId, id)
        );
        if (childCount != null && childCount > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_HAS_CHILDREN);
        }

        // ⑤ 该分类下还有账单时拒绝删除。
        //    ★ 用 selectCount + LambdaQueryWrapper 而不是手写 SQL：
        //      MP 会自动追加 deleted = 0（@TableLogic），不会漏掉逻辑删除条件。
        //      手写 @Select 时必须自己写 deleted = 0，两者别搞混。
        //    账单的 deleted = 0 同样由 MP 保证 —— 已删除账单不算"在用"。
        Long billCount = billMapper.selectCount(
                new LambdaQueryWrapper<Bill>()
                        .eq(Bill::getCategoryId, id)
        );
        if (billCount != null && billCount > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_HAS_BILLS);
        }

        // ⑥ 该分类下还有预算时拒绝删除。
        //    ★ 与 ⑤ 同一套思路：宁可拒绝，也不留"指向不存在分类"的悬空预算。
        //      悬空预算的已用量永远为 0、分类名补不出来，界面上会出现一条莫名其妙的预算。
        //    ★ 这里查的是【分类级预算】；账本级预算的 category_id 为 NULL，不受影响。
        Long budgetCount = budgetMapper.selectCount(
                new LambdaQueryWrapper<Budget>()
                        .eq(Budget::getCategoryId, id)
        );
        if (budgetCount != null && budgetCount > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_HAS_BUDGET);
        }

        // ⑦ 逻辑删除：@TableLogic 会改写成
        //    UPDATE t_category SET deleted = 1 WHERE id = ? AND deleted = 0
        //    历史账单仍能关联到这条记录，前端可显示分类名（标注"已删除"）
        categoryMapper.deleteById(id);
    }
}
