package com.sakuya.notesatwill.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sakuya.notesatwill.common.result.Result;
import com.sakuya.notesatwill.entity.Account;
import com.sakuya.notesatwill.entity.DTO.AccountRequest;
import com.sakuya.notesatwill.entity.DTO.AdjustRequest;
import com.sakuya.notesatwill.entity.DTO.TransferRequest;
import com.sakuya.notesatwill.entity.VO.AccountFlowVO;
import com.sakuya.notesatwill.service.AccountFlowService;
import com.sakuya.notesatwill.service.AccountService;
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
 * 资金账户接口。
 *
 * <p><b>本类所有接口都需要登录</b>，无需在 {@code SecurityConfig} 单独放行。
 * 身份一律用 {@code @AuthenticationPrincipal Long userId} 取 ——
 * 账户是纯私有数据，<b>userId 绝不能由前端传入</b>，否则能操作别人的账户余额。
 *
 * <p><b>★★ 关于余额：本类【没有】任何直接修改余额的接口。</b>
 * 原因是 {@code t_account.balance} 是冗余字段，只能由「资金流水服务」维护。
 * 想让余额变动，只有两条合法路径：
 * <ul>
 *   <li>记账时选了某个账户 → 由账单模块触发一条 BILL 流水</li>
 *   <li>账户模块的转账 / 手动校正 → 生成 TRANSFER / ADJUST 流水</li>
 * </ul>
 * 这两者都在 D2/D4 实现。届时余额的每一次变动都会在 {@code t_account_flow}
 * 里留下记录，对账公式 {@code balance == initBalance + ΣIN - ΣOUT} 才能成立。
 *
 * <p><b>统一响应纪律</b>：业务失败一律 HTTP 200 + {@code Result.code}，
 * 本类不加 {@code @ResponseStatus}。
 *
 * @author sakuya
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    /*
     * 撤销转账直接用流水服务：
     * 它做的事（按 transferNo 成对作废流水 + 回滚余额）全是资金流水的职责，
     * 没有账户层需要编排的内容，所以不在 AccountService 里包一层纯转发。理由见下方该方法注释。
     */
    private final AccountFlowService accountFlowService;

    /**
     * 查询当前用户的账户列表（带余额）。
     *
     * <p>默认账户置顶，其余按 {@code sort} 排序。
     * 前端记账页的"资产账户"选择器直接消费本接口 ——
     * 注意那里是<b>选填</b>的：不选账户时账单不产生任何资金流水。
     *
     * @param userId 当前登录用户 id（来自 token）
     */
    @GetMapping
    public Result<List<Account>> list(@AuthenticationPrincipal Long userId) {
        return Result.success(accountService.list(userId));
    }

    /**
     * 新建账户。
     *
     * <p>可传 {@code initBalance}（期初余额），服务端会把它同时写入
     * {@code initBalance} 与 {@code balance} —— 新建这一刻还没有流水，
     * 两者必然相等。
     *
     * <p><b>请求体里不要传 {@code balance}</b>：{@link AccountRequest} 里
     * 根本没有这个字段，传了会被 Jackson 忽略（全局配置了
     * {@code fail-on-unknown-properties: false}）。这是刻意设计，不是遗漏。
     *
     * @param userId  当前登录用户 id（来自 token）
     * @param request 账户信息
     */
    @PostMapping
    public Result<Account> create(@AuthenticationPrincipal Long userId,
                                  @Valid @RequestBody AccountRequest request) {
        return Result.success(accountService.create(userId, request));
    }

    /**
     * 修改账户（只能改名称、类型、图标、排序）。
     *
     * <p><b>★★ 请求体里的 {@code initBalance} 会被服务端忽略。</b>
     * 即使传了也不会改动期初余额 —— 因为改了会让对账公式失效。
     * 前端做编辑表单时，期初余额应当<b>只读展示</b>，不要做成可输入项。
     *
     * <p>如果确实需要修正余额（账实不符），正确做法是用后续实现的
     * 「手动校正」接口，它会生成一条 ADJUST 流水，余额变动可追溯、对账仍成立。
     *
     * @param userId  当前登录用户 id（来自 token）
     * @param id      要修改的账户 id
     * @param request 新的账户信息；{@code initBalance} 被忽略
     */
    @PutMapping("/{id}")
    public Result<Account> update(@AuthenticationPrincipal Long userId,
                                  @PathVariable Long id,
                                  @Valid @RequestBody AccountRequest request) {
        return Result.success(accountService.update(userId, id, request));
    }

    /**
     * 删除账户（逻辑删除）。
     *
     * <p><b>★ 该账户只要还有未删除的资金流水就会被拒绝</b>（{@code ACCOUNT_IN_USE 3024}）。
     * 这条规则不只是"防误删"，更是为了从源头堵住一个死角：
     * 若账户能带着流水被删掉，之后删除那些关联账单时，余额将无法回滚
     * （{@code addBalance} 匹配不到已删除的账户），用户会卡住。
     *
     * @param userId 当前登录用户 id（来自 token）
     * @param id     要删除的账户 id
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal Long userId,
                               @PathVariable Long id) {
        accountService.delete(userId, id);
        return Result.success();
    }

    /**
     * 转账：把金额从转出账户搬到转入账户。
     *
     * <pre>
     * POST /api/accounts/transfer
     * { "fromAccountId": 1, "toAccountId": 2, "amount": 100.00, "remark": "银行卡 转 微信零钱" }
     * → { "code": 200, "data": "3f2a9c...（32 位批次号）" }
     * </pre>
     *
     * <p><b>转账不产生任何账单</b> —— 它是账户之间搬钱，不是消费行为。
     * 落库的是两条资金流水（转出 OUT + 转入 IN），共享同一个批次号。
     *
     * <p><b>★ 请求体里没有 {@code direction}</b>：方向由服务端固定。
     * 让前端传方向只会多一个出错点，而且传反了余额会朝反方向跑、
     * <b>对账还查不出来</b>（流水与余额一致地错）。
     *
     * <p><b>★ 也不接受前端传批次号</b>：若允许传入，两次转账可能撞号，
     * 撤销时会一次删掉两批流水。
     *
     * <p>返回的批次号是<b>撤销转账的唯一凭据</b>，前端需要保存它。
     *
     * @param userId  当前登录用户 id（来自 token）
     * @param request 转账信息
     */
    @PostMapping("/transfer")
    public Result<String> transfer(@AuthenticationPrincipal Long userId,
                                   @Valid @RequestBody TransferRequest request) {
        return Result.success(accountService.transfer(userId, request));
    }

    /**
     * 撤销转账：按批次号把两条流水<b>一起</b>作废并回滚两个账户。
     *
     * <pre>
     * DELETE /api/accounts/transfer/{transferNo}
     * </pre>
     *
     * <p><b>★ 它本质是转账的「对冲操作」</b>：转账是"从 A 搬到 B"，
     * 撤销就是把这次搬动整个抹掉 —— 两个账户各自反向抵消，而不是"只退一边"。
     *
     * <p><b>★ 必须成对处理</b>：一次转账有两条流水（一条 OUT、一条 IN），
     * 只删一条会让两个账户同时错 —— 一个恢复了、另一个没恢复，
     * 凭空多出或少掉一笔钱，对账会立刻不平。
     *
     * <p>批次号不存在、或不属于当前用户时返回 {@code ACCOUNT_TRANSFER_NOT_FOUND(3023)}
     * （两种情况合并成同一个错误码，避免被用来探测别人的批次号）。
     *
     * <p><b>为什么这里直接调 {@code AccountFlowService}，而不经 {@code AccountService}</b>：
     * 撤销转账要做的事情（按批次号作废流水 + 回滚余额）<b>全部是资金流水的职责</b>，
     * 不需要任何账户层面的编排 —— {@code user_id} 已经在查询条件里，
     * 归属过滤与越权防护在流水服务内部完成。
     * 在 {@code AccountService} 里再包一层纯转发的方法，只会让"账户元数据与余额分离"
     * 这条纪律看起来名不副实。
     *
     * <p>对比同一模块里另外三个接口：{@code transfer} / {@code adjust} / {@code flows}
     * 之所以走 {@code AccountService}，是因为它们<b>确实做了</b>账户层的编排
     * （校验两个账户归属、查余额是否充足、生成批次号 / 校验归属）。
     * <b>分界线是"有没有增加价值"，而不是"端点在哪个路径下"。</b>
     *
     * @param userId     当前登录用户 id（来自 token）
     * @param transferNo 转账批次号
     */
    @DeleteMapping("/transfer/{transferNo}")
    public Result<Void> cancelTransfer(@AuthenticationPrincipal Long userId,
                                       @PathVariable String transferNo) {
        accountFlowService.removeByTransferNo(userId, transferNo);
        return Result.success();
    }

    /**
     * 手动校正余额到指定值，并留下一笔 ADJUST 流水。
     *
     * <pre>
     * POST /api/accounts/5/adjust
     * { "targetBalance": 1000.00, "remark": "银行卡实际余额对不上，手动修正" }
     * </pre>
     *
     * <p><b>★ 传的是「目标余额」而不是「调整额」</b>：用户知道"应该是 1000"，
     * 但不知道"和现在差多少"，差额由服务端算。
     *
     * <p><b>★ 校正也会写一条流水</b>（IN 或 OUT），所以余额变动可追溯、
     * 对账公式依然成立。这是本接口与"直接改余额"的本质区别 ——
     * 后者会让对账立刻崩掉。
     *
     * <p><b>校正到与当前相同的余额不会报错</b>，也不会写一条金额为 0 的流水
     * （{@code amount} 的约定是恒为正数），而是直接返回成功。
     *
     * <p>本接口<b>不会改动 {@code initBalance}</b>（期初余额不可变）。
     *
     * @param userId  当前登录用户 id（来自 token）
     * @param id      账户 id
     * @param request 目标余额 + 校正原因
     */
    @PostMapping("/{id}/adjust")
    public Result<Void> adjust(@AuthenticationPrincipal Long userId,
                               @PathVariable Long id,
                               @Valid @RequestBody AdjustRequest request) {
        accountService.adjust(userId, id, request);
        return Result.success();
    }

    /**
     * 某账户的资金流水列表（BILL / TRANSFER / ADJUST 三类来源）。
     *
     * <pre>
     * GET /api/accounts/5/flows?page=1&amp;size=20
     * GET /api/accounts/5/flows?flowType=TRANSFER      → 只看转账
     * </pre>
     *
     * <p><b>★ 转账流水会带上「对方账户名」</b>，否则列表上一条"转出 100"
     * 完全看不懂转给谁了。补查由服务端批量完成，整页只多两次查询。
     *
     * <p><b>⚠ 路由顺序</b>：本方法注册为 {@code /{id}/flows}，
     * 与上面的 {@code /transfer}、{@code /{id}} 都不冲突
     * （{@code /transfer} 是字面路径优先；{@code /{id}/flows} 比 {@code /{id}} 多一段）。
     *
     * @param userId   当前登录用户 id（来自 token）
     * @param id       账户 id
     * @param flowType 可选：只看某一类来源（BILL / TRANSFER / ADJUST）
     * @param page     页码，从 1 开始；缺省 1
     * @param size     每页条数；缺省 20，<b>服务端强制上限 100</b>
     */
    @GetMapping("/{id}/flows")
    public Result<Page<AccountFlowVO>> flows(@AuthenticationPrincipal Long userId,
                                             @PathVariable Long id,
                                             @RequestParam(required = false) String flowType,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        int safePage = (page == null || page < 1) ? 1 : page;
        int safeSize = (size == null || size < 1) ? 20 : Math.min(size, 100);
        return Result.success(accountService.listFlows(userId, id, flowType, safePage, safeSize));
    }
}
