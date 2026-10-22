package com.sakuya.notesatwill.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sakuya.notesatwill.common.result.Result;
import com.sakuya.notesatwill.entity.Bill;
import com.sakuya.notesatwill.entity.DTO.BillQuery;
import com.sakuya.notesatwill.entity.DTO.BillRequest;
import com.sakuya.notesatwill.entity.VO.BillSummaryVO;
import com.sakuya.notesatwill.entity.VO.BillVO;
import com.sakuya.notesatwill.service.BillService;
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

/**
 * 记账接口。
 *
 * <p><b>本类所有接口都需要登录</b>，无需在 {@code SecurityConfig} 单独放行：
 * 它们属于 {@code anyRequest().authenticated()}。
 * 身份一律用 {@code @AuthenticationPrincipal Long userId} 取 ——
 * <b>账单是纯私有数据，userId 绝不能由前端传入</b>。
 *
 * <p><b>统一响应纪律</b>：业务失败一律 HTTP 200 + {@code Result.code}，
 * 本类不加任何 {@code @ResponseStatus}。
 *
 * @author sakuya
 */
@RestController
@RequestMapping("/api/bills")
@RequiredArgsConstructor
public class BillController {

    private final BillService billService;

    /**
     * 分页查询账单（多条件筛选）。
     *
     * <p><b>所有查询参数都是可选的</b>，不传即不按该条件筛：
     * <pre>
     *   GET /api/bills                                        → 全部（分页）
     *   GET /api/bills?type=EXPENSE&amp;page=2                  → 只看支出，第 2 页
     *   GET /api/bills?accountId=3                            → 只看用了 3 号账户的账
     *   GET /api/bills?startTime=2026-10-01 00:00:00
     *                 &amp;endTime=2026-11-01 00:00:00            → 只看 10 月
     *   GET /api/bills?keyword=房租                            → 备注含"房租"
     * </pre>
     *
     * <p><b>时间参数格式</b>：{@code yyyy-MM-dd HH:mm:ss}（与全局
     * {@code JacksonConfig} 的序列化格式一致，前后端对称）。
     *
     * <p><b>{@code size} 由服务端强制上限 100</b>（{@link BillQuery#MAX_SIZE}），
     * 防止前端传 {@code size=999999} 把整表拉进内存。
     *
     * @param userId 当前登录用户 id（来自 token）
     * @param query  筛选条件，由 Spring 自动绑定 query 参数
     */
    @GetMapping
    public Result<Page<BillVO>> page(@AuthenticationPrincipal Long userId,
                                     BillQuery query) {
        return Result.success(billService.pageBills(userId, query));
    }

    /**
     * 账单详情。
     *
     * <p>返回的 {@link BillVO} 与列表项结构<b>完全一致</b>（都经同一个组装方法），
     * 所以前端可以复用同一套渲染逻辑。
     *
     * @param userId 当前登录用户 id（来自 token）
     * @param id     账单 id
     */
    @GetMapping("/{id}")
    public Result<BillVO> detail(@AuthenticationPrincipal Long userId,
                                 @PathVariable Long id) {
        return Result.success(billService.getBill(userId, id));
    }

    /**
     * 列表页顶部汇总：本月收入 / 本月支出 / 结余。
     *
     * <p><b>口径固定为「账本 + 自然月」</b>（设计文档已定死）：
     * 只统计当前账本、当前自然月内的账单；
     * <b>不跟随</b>分类 / 账户 / 关键词等筛选条件 ——
     * 卡片回答的是"这个账本这个月花了多少"，与用户在筛选栏里选了什么无关。
     *
     * <pre>
     * GET /api/bills/summary?bookId=1
     * →
     * { "bookId": 1, "bookName": "默认账本", "month": "2026-10",
     *   "income": 1000.00, "expense": 250.00, "surplus": 750.00 }
     * </pre>
     *
     * <p><b>{@code bookId} 必传</b>（缺了返回 1000 参数错误），
     * 因为本接口的口径就是"当前选中账本"，没有账本就没有口径。
     *
     * <p>注意字段名是 <b>{@code surplus}（结余）而不是 {@code balance}</b> ——
     * 后者在本项目里指"账户余额"，两者语义完全不同，不要混用。
     *
     * <p><b>⚠ 路由顺序</b>：本方法必须以字面路径 {@code /summary} 注册，
     * 且 Spring 会优先匹配字面路径而不是上面的 {@code /{id}} 路径变量，
     * 所以两者不冲突（已用端到端测试确认命中正确）。
     *
     * @param userId 当前登录用户 id（来自 token）
     * @param bookId 账本 id，必须属于当前用户
     */
    @GetMapping("/summary")
    public Result<BillSummaryVO> summary(@AuthenticationPrincipal Long userId,
                                         @RequestParam Long bookId) {
        return Result.success(billService.summary(userId, bookId));
    }

    /**
     * 新增账单。
     *
     * <p><b>★ {@code accountId} 是选填项</b> —— 这是本接口最重要的特性：
     * <pre>
     *   不传 accountId → 只记一笔消费，【不产生任何资金流水，账户余额纹丝不动】
     *   传了 accountId → 额外写一条 BILL 流水，该账户余额随之变动（同一事务）
     * </pre>
     * 原因：记账与账户是面向用户的两个独立功能，由用户自主决定是否关联。
     *
     * <p>返回新建的账单实体（含回填 id）。注意这里是 {@code Bill} 而非
     * {@link BillVO} —— 刚创建的账单还没有补查名字的必要，
     * 前端拿到 id 后可自行决定是否重新拉列表。
     *
     * @param userId  当前登录用户 id（来自 token）
     * @param request 账单信息；{@code accountId} 可为 null
     */
    @PostMapping
    public Result<Bill> create(@AuthenticationPrincipal Long userId,
                               @Valid @RequestBody BillRequest request) {
        return Result.success(billService.createBill(userId, request));
    }

    /**
     * 修改账单。
     *
     * <p><b>★ 语义是「整体替换」</b>：请求体就是账单的新完整状态。
     * <b>{@code accountId} 必须显式传</b> —— 传 id 表示关联该账户，传 {@code null} 表示有意解除关联。
     * 前端不要因为"这次没改账户"就省略这个字段，否则会被当成"要解除关联"。
     *
     * <p><b>账户三态切换</b>（三种情况都与账单更新在同一事务内）：
     * <pre>
     *   无 → 有：补建一条资金流水
     *   有 → 无：删掉流水并把余额回滚
     *   有 → 有：换账户 = 删旧流水 + 建新流水（两次余额变动）
     * </pre>
     * 实现方式是「全删重建」：先 {@code removeByBillId} 回滚旧流水，再按新参数写新流水。
     * 余额变动一律通过 {@code AccountFlowService}（唯一入口）。
     *
     * @param userId  当前登录用户 id
     * @param id      要修改的账单 id
     * @param request 新的账单信息
     */
    @PutMapping("/{id}")
    public Result<Bill> update(@AuthenticationPrincipal Long userId,
                               @PathVariable Long id,
                               @Valid @RequestBody BillRequest request) {
        return Result.success(billService.updateBill(userId, id, request));
    }

    /**
     * 删除账单。
     *
     * <p>删除账单不只是删一行，同一事务里做两件事：
     * <ol>
     *   <li>删除该账单关联的 BILL 流水（若用户当时选了账户），<b>并把余额加回去</b></li>
     *   <li>逻辑删除 {@code t_bill}</li>
     * </ol>
     * <b>特别注意</b>：没选账户的账单<b>删除时不会触碰任何余额</b> ——
     * 它本来就没有流水可删。
     *
     * <p>D9 还会在此基础上加"同时删除对应的账单卡片消息"（双向级联删除）。
     *
     * @param userId 当前登录用户 id
     * @param id     要删除的账单 id
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal Long userId,
                               @PathVariable Long id) {
        billService.deleteBill(userId, id);
        return Result.success();
    }
}
