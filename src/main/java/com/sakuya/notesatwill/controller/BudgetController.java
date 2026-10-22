package com.sakuya.notesatwill.controller;

import com.sakuya.notesatwill.common.result.Result;
import com.sakuya.notesatwill.entity.DTO.BudgetRequest;
import com.sakuya.notesatwill.entity.VO.BudgetCalendarVO;
import com.sakuya.notesatwill.entity.VO.BudgetStatusVO;
import com.sakuya.notesatwill.entity.VO.BudgetVO;
import com.sakuya.notesatwill.service.BudgetService;
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
 * 预算接口。
 *
 * <p><b>★ 预算分两类，靠 {@code categoryId} 是否为 null 区分</b>：
 * <ul>
 *   <li><b>账本级</b>（{@code categoryId = null}）—— 整个账本当月总支出不超过额度</li>
 *   <li><b>分类级</b>（{@code categoryId = 分类 id}）—— 该分类<b>及其子分类</b>
 *       当月支出不超过额度</li>
 * </ul>
 *
 * <p><b>★ 配置与执行状态是两个接口，不是一个</b>：
 * <ul>
 *   <li>{@code GET /api/budgets} —— 只查配置（额度、阈值），不聚合，
 *       所以列表有 100 条预算也是常数级查询</li>
 *   <li>{@code GET /api/budgets/status} —— 实时聚合已用量。
 *       按账本分组聚合，一个账本 2 次查询，<b>与预算条数无关</b></li>
 * </ul>
 * 如果把"已用/剩余"塞进列表接口，每次打开预算页都要跑一遍聚合 —— 而且大多数人
 * 只是想看看自己设了哪些预算。
 *
 * <p><b>★★ 剩余额度永远不落库</b>，由"额度 − 实时已用"算出。
 * 详见 {@link BudgetStatusVO} 的类注释。
 *
 * @author sakuya
 */
@RestController
@RequestMapping("/api/budgets")
@RequiredArgsConstructor
public class BudgetController {

    private final BudgetService budgetService;

    /**
     * 查预算配置列表。
     *
     * <pre>
     * GET /api/budgets?bookId=1    （bookId 可选，不传返回全部账本的预算）
     * </pre>
     *
     * <p>返回的 {@code categoryId} 为 null 表示该条是账本级预算。
     *
     * @param userId 当前登录用户 id（来自 token）
     * @param bookId 可选：只看某个账本
     */
    @GetMapping
    public Result<List<BudgetVO>> list(@AuthenticationPrincipal Long userId,
                                       @RequestParam(required = false) Long bookId) {
        return Result.success(budgetService.list(userId, bookId));
    }

    /**
     * 新建预算。
     *
     * <pre>
     * POST /api/budgets
     * { "bookId": 1, "categoryId": null, "amount": 3000.00, "alertThreshold": 80 }
     * </pre>
     *
     * <p><b>★ {@code categoryId = null} 就是账本级预算</b>，与"不传"等价。
     *
     * <p><b>★ 同账本同分类只允许一条</b>（返回 4011）。这条约束只能靠
     * Service 拦，因为 {@code category_id} 可为 NULL，而 MySQL 的 UNIQUE 索引
     * 允许多个 NULL —— 数据库层面根本管不住账本级预算的重复。
     *
     * @param userId  当前登录用户 id
     * @param request 预算信息
     */
    @PostMapping
    public Result<BudgetVO> create(@AuthenticationPrincipal Long userId,
                                   @Valid @RequestBody BudgetRequest request) {
        return Result.success(budgetService.create(userId, request));
    }

    /**
     * 修改预算（<b>整体替换</b>）。
     *
     * <pre>
     * PUT /api/budgets/5
     * { "bookId": 1, "categoryId": 1001, "amount": 2000.00, "alertThreshold": 90 }
     * </pre>
     *
     * <p><b>★ 传 {@code categoryId = null} 表示"改成账本级预算"</b>，不是"不修改"。
     * PUT 是整体替换语义，与账单接口一致 —— 漏传会被当成"要取消分类"。
     *
     * <p><b>★ {@code alertThreshold} 不传会重置成默认 80</b>（而不是保留原值），
     * 同样因为这是整体替换。
     *
     * @param userId  当前登录用户 id
     * @param id      预算 id
     * @param request 新的预算信息
     */
    @PutMapping("/{id}")
    public Result<BudgetVO> update(@AuthenticationPrincipal Long userId,
                                   @PathVariable Long id,
                                   @Valid @RequestBody BudgetRequest request) {
        return Result.success(budgetService.update(userId, id, request));
    }

    /**
     * 删除预算。
     *
     * <pre>
     * DELETE /api/budgets/5
     * </pre>
     *
     * <p>预算没有下游数据（不产生流水、不影响余额），删除是安全的。
     *
     * @param userId 当前登录用户 id
     * @param id     预算 id
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal Long userId,
                               @PathVariable Long id) {
        budgetService.delete(userId, id);
        return Result.success();
    }

    /**
     * 查预算执行状态（<b>实时聚合，支持历史月份</b>）。
     *
     * <pre>
     * GET /api/budgets/status?bookId=1&amp;month=2026-10
     * →
     * [{ "budgetId": 1, "amount": 3000.00, "used": 1250.00, "remaining": 1750.00,
     *    "usedPercent": 41.67, "status": "NORMAL", "alertThreshold": 80,
     *    "month": "2026-10", ... }]
     * </pre>
     *
     * <p><b>★ 新增一笔支出后，{@code used} 会立即变化</b> —— 因为它是每次现查的，
     * 不存在"缓存了但没刷新"的可能。
     *
     * <p><b>★ {@code status} 的边界</b>：
     * {@code NORMAL} / {@code WARNING}（达到阈值）/ {@code EXCEEDED}（<b>严格超过</b>额度）。
     * 正好花满额度算 WARNING 而不是 EXCEEDED —— "刚好花完计划"不算超支。
     *
     * @param userId 当前登录用户 id
     * @param bookId 可选：只看某个账本
     * @param month  月份 {@code yyyy-MM}；不传取当前月
     */
    @GetMapping("/status")
    public Result<List<BudgetStatusVO>> status(@AuthenticationPrincipal Long userId,
                                               @RequestParam(required = false) Long bookId,
                                               @RequestParam(required = false) String month) {
        return Result.success(budgetService.status(userId, bookId, month));
    }

    /**
     * 预算日历视图：该月每天的支出合计。
     *
     * <pre>
     * GET /api/budgets/calendar?budgetId=1&amp;month=2026-10
     * →
     * { "budgetId": 1, "month": "2026-10", "amount": 3000.00, "used": 1250.00,
     *   "remaining": 1750.00,
     *   "days": [ { "date": "2026-10-01", "amount": 0, "hasData": false }, ... ] }
     * </pre>
     *
     * <p><b>★★ {@code days} 一定是当月完整天数（28~31），缺的日期补 0</b>。
     * 前端可以直接按下标定位格子，不必自己补。
     * 验收要求"各天之和 = 当月已用量"，所以这里也把 {@code used} 一起返回。
     *
     * <p><b>★ {@code hasData} 用来区分"这天没花钱"和"这天花了 0 元"</b> ——
     * 补 0 之后光看 amount 是分不清的。
     *
     * @param userId   当前登录用户 id
     * @param budgetId 预算 id
     * @param month    月份 {@code yyyy-MM}；不传取当前月
     */
    @GetMapping("/calendar")
    public Result<BudgetCalendarVO> calendar(@AuthenticationPrincipal Long userId,
                                             @RequestParam Long budgetId,
                                             @RequestParam(required = false) String month) {
        return Result.success(budgetService.calendar(userId, budgetId, month));
    }
}
