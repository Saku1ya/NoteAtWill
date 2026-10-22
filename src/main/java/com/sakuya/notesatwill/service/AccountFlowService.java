package com.sakuya.notesatwill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Account;
import com.sakuya.notesatwill.entity.AccountFlow;
import com.sakuya.notesatwill.entity.VO.AccountCheckVO;
import com.sakuya.notesatwill.entity.VO.AccountFlowVO;
import com.sakuya.notesatwill.mapper.AccountFlowMapper;
import com.sakuya.notesatwill.mapper.AccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 资金流水服务 —— <b>全项目唯一允许修改 {@code t_account.balance} 的地方</b>。
 *
 * <p><b>★★ 为什么这条纪律如此重要</b>：{@code balance} 是一个<b>派生数据的缓存</b>，
 * 它的严格定义是：
 * <pre>
 *   balance ≡ init_balance + SUM(方向 IN 的金额) - SUM(方向 OUT 的金额)
 * </pre>
 * 它存在的唯一目的是"避免每次查账户列表都做聚合"。
 * 因此余额<b>不能独立存在</b>，只能作为流水的"伴随写入" ——
 * 每次余额变动都必须与一条流水同时发生。
 *
 * <p>一旦有代码绕过本类直接 UPDATE 余额，对账公式就会永久性对不上，
 * 而且很难定位是哪一次操作弄坏的。所以：
 * <ul>
 *   <li>本类只对外暴露「记一条流水」{@link #record} 与「删一条流水」{@link #remove}</li>
 *   <li>全局搜索 {@code balance}，除 {@code AccountMapper.addBalance} 外不应有第二处修改</li>
 * </ul>
 *
 * <p><b>为什么"算 delta"这件事也放在本类，而不是让调用方算好再传进来</b>：
 * 若把方向换算交给调用方（记账 / 转账 / 手动校正各一处），
 * 同一个公式就散落三处，任一处写反或漏写，对账即崩。
 * 收敛到这里后，调用方只需诚实说明"钱往哪走、多少钱"，
 * <b>不必知道余额是加还是减</b>。
 *
 * @author sakuya
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountFlowService {

    private final AccountFlowMapper accountFlowMapper;
    private final AccountMapper accountMapper;

    /** 方向：转入（余额增加） */
    public static final String DIRECTION_IN = "IN";
    /** 方向：转出（余额减少） */
    public static final String DIRECTION_OUT = "OUT";
    /** 流水来源：记账产生 */
    public static final String FLOW_TYPE_BILL = "BILL";
    /** 流水来源：转账（一次两条，共享 transferNo） */
    public static final String FLOW_TYPE_TRANSFER = "TRANSFER";
    /** 流水来源：手动校正余额 */
    public static final String FLOW_TYPE_ADJUST = "ADJUST";



    /**
     * 记录一条资金流水，并同步更新账户余额。<b>两步必须在同一事务内</b>。
     *
     * <p><b>★ 执行顺序：先改余额、再写流水。</b>
     * 这样账户不合法时（不存在 / 不属于该用户 / 已删除）
     * {@code addBalance} 返回 0，立刻抛异常，<b>流水根本不会被插入</b>，
     * 省掉一次无谓的插入 + 回滚。反过来先插流水虽然事务也会回滚，
     * 但白做一次写入。（两者功能等价，只是前者更干净。）
     *
     * <p><b>★ 为什么必须检查 {@code addBalance} 的返回值</b>：
     * 返回 0 表示没有匹配到可更新的账户。若不检查就继续插流水，
     * 会出现"流水写了、余额没动"的脏数据 —— 这类数据<b>对账时才会暴露</b>，
     * 而且因为流水看起来完全正常，排查起来非常困难。
     * 抛异常让事务回滚，两者要么都成功、要么都不发生。
     *
     * <p><b>★ 关于金额符号
     * <pre>
     *   t_account_flow.amount  恒为【正数】，方向由 direction 表达（便于人阅读）
     *   addBalance 的 delta    【可正可负】，只需要净变化量
     * </pre>
     * 所以调用方传进来的 {@code amount} 必须是正数，由本方法按
     * {@link #signedDelta} 换算成带符号的 delta。
     * <b>不要在外面就把 amount 传成负数</b>，否则流水表里会出现负金额，
     * 而"金额恒为正"是这个表的基本约定，统计与对账都依赖它。
     *
     *
     * @param userId    当前登录用户 id（来自 token）
     * @param accountId 发生变动的账户
     * @param flowType  来源：BILL / TRANSFER / ADJUST
     * @param billId    关联账单 id，仅 BILL 类型有值；其它传 null
     * @param direction 方向：IN 转入 / OUT 转出
     * @param amount    变动金额，<b>必须为正数</b>
     * @param flowTime  资金变动时间；BILL 类型应与 {@code bill_time} 一致
     * @param remark    备注，可为 null
     * @throws BusinessException 金额非正数、方向非法、账户不存在/不属于该用户/已删除
     */
    @Transactional
    public void record(Long userId, Long accountId,
                       String flowType, Long billId,
                       String direction, BigDecimal amount,
                       LocalDateTime flowTime, String remark) {
        doRecord(userId, accountId, flowType, billId, null, direction, amount, flowTime, remark);
    }

    /**
     * 转账：在两个账户之间搬钱，写两条<b>共享同一 {@code transferNo}</b> 的 TRANSFER 流水。
     *
     * <p><b>★ 为什么单独给转账开一个入口，而不是给 {@link #record} 加 transferNo 参数</b>：
     * <ol>
     *   <li><b>方向由本方法内部固定</b>（from → OUT、to → IN），
     *       调用方<b>根本不需要传 direction</b> —— 转账要连写两条流水，
     *       方向是最容易写反的地方，而写反了余额就会朝反方向跑，
     *       且<b>对账查不出来</b>（流水与余额一致地错）</li>
     *   <li>{@code transferNo} 只在转账路径出现，不必让账单/校正的调用点去传 null</li>
     *   <li>语义更贴业务：不是"记一条流水"，而是"在两个账户间搬钱"</li>
     * </ol>
     *
     * <p><b>★ 两条流水必须共享 {@code transferNo}</b> —— 撤销转账要靠它成对删除。
     * 少了它，撤销时按批次号查不到任何流水，转账就永远无法回滚。
     *
     * <p>本方法<b>不做余额是否充足的判断</b>：那是转账编排（{@code AccountService.transfer}）的职责。
     * 这里只负责"施加影响"，与 {@link #record} 的分工一致。
     *
     * <p>两次 {@code doRecord} 在同一事务内，任一条失败则整体回滚 ——
     * 不会出现"只转出没转入"的半截转账。
     *
     * @param userId        当前登录用户 id
     * @param fromAccountId 转出账户
     * @param toAccountId   转入账户，<b>必须与转出不同</b>（相同应在编排层拦住）
     * @param amount        转账金额，<b>必须为正数</b>
     * @param transferNo    批次号，一次转账的两条流水共享；建议 32 位
     * @param flowTime      资金变动时间
     * @param remark        备注，转账时可记"银行卡 转 微信零钱"；两条写同一个
     * @throws BusinessException 金额非正数（4001）；任一账户不存在/不属于该用户/已删除（3020）
     */
    @Transactional
    public void recordTransfer(Long userId, Long fromAccountId, Long toAccountId,
                               BigDecimal amount, String transferNo,
                               LocalDateTime flowTime, String remark) {
        // 方向在此写死：转出记 OUT、转入记 IN。
        // 调用方无法传错方向，这是本方法存在的最大理由。
        doRecord(userId, fromAccountId, FLOW_TYPE_TRANSFER, null, transferNo,
                DIRECTION_OUT, amount, flowTime, remark);
        doRecord(userId, toAccountId, FLOW_TYPE_TRANSFER, null, transferNo,
                DIRECTION_IN, amount, flowTime, remark);
    }

    /**
     * 写一条流水并同步更新余额 —— <b>本类唯一的写库路径</b>。
     *
     * <p>{@link #record} 与 {@link #recordTransfer} 都收敛到这里，
     * 保证"金额校验 / 方向校验 / 原子增量 / 流水落库"只有一份实现。
     *
     * @param transferNo 转账批次号；非转账传 {@code null}
     */
    private void doRecord(Long userId, Long accountId, String flowType, Long billId,
                          String transferNo, String direction, BigDecimal amount,
                          LocalDateTime flowTime, String remark) {

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BILL_AMOUNT_INVALID);
        }

        if (!DIRECTION_IN.equals(direction) && !DIRECTION_OUT.equals(direction)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "资金方向只能是 IN 或 OUT");
        }

        BigDecimal delta = signedDelta(direction, amount);
        int affected = accountMapper.addBalance(userId, accountId, delta);
        if (affected == 0) {
            //校验数据库操作是否成功
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }

        AccountFlow flow = new AccountFlow();
        flow.setUserId(userId);
        flow.setAccountId(accountId);
        flow.setFlowType(flowType);
        flow.setBillId(billId);
        flow.setTransferNo(transferNo);
        flow.setDirection(direction);
        flow.setAmount(amount);
        flow.setFlowTime(flowTime);
        flow.setRemark(remark);
        accountFlowMapper.insert(flow);

        log.debug("记资金流水：userId={}, accountId={}, flowType={}, direction={}, amount={}, delta={}, transferNo={}",
                userId, accountId, flowType, direction, amount, delta, transferNo);
    }

    /**
     * 把「方向 + 正金额」换算成带符号的余额变化量。
     * @param direction IN / OUT
     * @param amount    正数金额
     * @return IN → {@code +amount}；OUT → {@code -amount}
     */
    private BigDecimal signedDelta(String direction, BigDecimal amount) {
        return DIRECTION_IN.equals(direction) ? amount : amount.negate();
    }

    /*
     * ⚠ 这里【没有】remove(userId, flowId)（单条流水删除）。
     *
     * 它原本是 D3 留的一个桩（throw UnsupportedOperationException），
     * 但到 D6 为止全项目没有任何调用方 —— 因为【业务上不存在"删单条流水"这个动作】：
     *   · 删账单 → removeByBillId（该账单的流水一起删）
     *   · 撤销转账 → removeByTransferNo（成对的两条一起删）
     *   · 手动校正 → 不删流水，而是新增一条 ADJUST 流水
     *
     * 试图"删掉某一条流水"会破坏资金流水的完整性：
     *   - 删掉转账的 IN 那条，钱就凭空多出来
     *   - 删掉账单流水但账单还在，账单与余额就对不上
     *
     * 所以没有"单条删除"的入口。若将来真的需要（比如后台运维纠正脏数据），
     * 应当新写一个明确命名的方法（如 removeSingleForRepair）并配套写审计日志，
     * 而不是复用这个通用名字 —— 否则它迟早被当普通删接口用。
     */

    /**
     * 按账单 id 删除其关联的资金流水，并把余额回滚。
     *
     * <p><b>它是 {@link #record} 的逆运算</b>，供「修改账单」（账户有 → 无 / 换账户）
     * 与「删除账单」调用 —— 也是 D3 里那两个方法的共同依赖。
     *
     * @param userId 当前登录用户 id；查询条件带它，因此<b>不可能动到别人的流水</b>
     * @param billId 账单 id
     * @return <b>实际回滚了几条流水</b>（{@code 0} 表示该账单原本就没有账户关联）。
     *         调用方可用它区分"用户主动取消账户"与"前端漏传 accountId"两种情况，
     *         见 {@code BillService.updateBill} 的用法
     * @throws BusinessException 参数为 null（4000）；或流水指向的账户已不可用（3020）
     */
    @Transactional
    public int removeByBillId(Long userId, Long billId) {

        if (userId == null || billId == null) {
            throw new BusinessException(ErrorCode.BILL_NOT_FOUND);
        }

        // ---------- ① 查该账单关联的 BILL 流水 ----------
        // ★ user_id 必须作为条件：即使上层漏了归属校验，这里也动不到别人的数据。
        // ★ flow_type 限死 BILL：转账/校正流水不属于账单，不该被本方法波及。
        // ★ 不手写 SQL，用 wrapper 让 @TableLogic 自动追加 deleted = 0 ——
        //   否则已删除的流水会被再删一次（并对余额重复回滚）。
        List<AccountFlow> flows = accountFlowMapper.selectList(
                new LambdaQueryWrapper<AccountFlow>()
                        .eq(AccountFlow::getUserId, userId)
                        .eq(AccountFlow::getFlowType, FLOW_TYPE_BILL)
                        .eq(AccountFlow::getBillId, billId));

        // ---------- ② 没有流水 → 静默返回（正常业务情况，不是错误）----------
        if (flows.isEmpty()) {
            log.debug("账单没有关联资金流水，无需回滚余额：userId={}, billId={}", userId, billId);
            return 0;
        }

        // ---------- ③ 逐条反向回滚余额 + 逻辑删除流水 ----------
        rollbackAndDelete(userId, flows);
        return flows.size();
    }

    /**
     * 撤销一次转账：把它那两条 TRANSFER 流水<b>成对</b>删掉并回滚两个账户。
     *
     * <p><b>★ 必须按 {@code transferNo} 成对删</b> —— 一条 OUT、一条 IN，
     * 只删一条会让两个账户同时错（一个恢复了、另一个没恢复）。
     *
     * <p>与 {@link #removeByBillId} 的关系：两者<b>只有"查哪些流水"不同</b>，
     * "回滚 + 删除"的逻辑完全一样，因此共用私有的 {@link #rollbackAndDelete}。
     * 那个循环体里装着余额的符号逻辑，两处各写一份迟早会分叉。
     *
     * <p><b>★ 为什么查到几条就回滚几条，而不是"必须是 2 条才处理"</b>：
     * 若因数据异常只剩 1 条，回滚它能<b>恢复"流水 ↔ 余额"的一致性</b>；
     * 而直接拒绝操作会让这个不一致永久留存。所以选择"全部回滚 + 警告日志"。
     *
     * @param userId     当前登录用户 id；查询条件带它，因此不可能动到别人的流水
     * @param transferNo 转账批次号
     * @return 实际回滚的流水条数（正常为 2）
     * @throws BusinessException 批次号不存在或不属于该用户（3023）；
     *                           或流水指向的账户已不可用（3020，此时整体回滚）
     */
    @Transactional
    public int removeByTransferNo(Long userId, String transferNo) {
        if (userId == null || transferNo == null || transferNo.isBlank()) {
            throw new BusinessException(ErrorCode.ACCOUNT_TRANSFER_NOT_FOUND);
        }

        // 不手写 SQL，用 wrapper 让 @TableLogic 自动追加 deleted = 0；
        // ★ 必须带 userId 与 flowType，避免误伤别人的流水或非转账流水
        List<AccountFlow> flows = accountFlowMapper.selectList(
                new LambdaQueryWrapper<AccountFlow>()
                        .eq(AccountFlow::getUserId, userId)
                        .eq(AccountFlow::getFlowType, FLOW_TYPE_TRANSFER)
                        .eq(AccountFlow::getTransferNo, transferNo));

        if (flows.isEmpty()) {
            throw new BusinessException(ErrorCode.ACCOUNT_TRANSFER_NOT_FOUND);
        }

        if (flows.size() != 2) {
            // 一次转账应恰好两条。出现别的条数说明数据异常，
            // 但仍把查到的全部回滚掉，让余额与现存流水重新自洽。
            log.warn("撤销转账时流水条数异常（应为 2）：userId={}, transferNo={}, 实际={}",
                    userId, transferNo, flows.size());
        }

        rollbackAndDelete(userId, flows);
        log.info("撤销转账：userId={}, transferNo={}, 回滚 {} 条流水", userId, transferNo, flows.size());
        return flows.size();
    }

    /**
     * 逐条「反向回滚余额 + 逻辑删除流水」—— <b>所有撤销路径的唯一实现</b>。
     *
     * <p>被 {@link #removeByBillId} 与 {@link #removeByTransferNo} 共用。
     *
     * <p><b>★ 方向必须取反</b>：原来是 IN（余额曾 +amount）就减回去，原来是 OUT 就加回去。
     * 复用 {@link #signedDelta} 再取负，保证与写入路径的换算<b>严格对称</b>。
     *
     * <p><b>★ 回滚失败时抛异常，绝不"跳过回滚但把流水删掉"</b> ——
     * 那会留下"流水没了、余额还是错的"这种更糟、且对账再也查不出的状态。
     *
     * @param userId 当前登录用户 id
     * @param flows  待回滚的流水（调用方已做过归属过滤）
     * @throws BusinessException 流水指向的账户不存在/不属于该用户/已删除（3020）
     */
    private void rollbackAndDelete(Long userId, List<AccountFlow> flows) {
        for (AccountFlow flow : flows) {
            BigDecimal reverseDelta = signedDelta(flow.getDirection(), flow.getAmount()).negate();

            int affected = accountMapper.addBalance(userId, flow.getAccountId(), reverseDelta);
            if (affected == 0) {
                throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
            }

            // 逻辑删除该流水。用 deleteById 让 @TableLogic 把它转成 UPDATE ... SET deleted=1；
            // ★ 千万不要用 setDeleted(...) —— 那只是改内存里的对象，不会产生任何 SQL。
            accountFlowMapper.deleteById(flow.getId());

            log.info("回滚资金流水：userId={}, flowId={}, accountId={}, flowType={}, direction={}, amount={}, reverseDelta={}",
                    userId, flow.getId(), flow.getAccountId(), flow.getFlowType(),
                    flow.getDirection(), flow.getAmount(), reverseDelta);
        }
    }

    // ========================================================================
    // 流水查询与账户可删性判断
    // ========================================================================

    /**
     * 该账户是否还有<b>未删除</b>的资金流水。
     *
     * <p>供 {@code AccountService.delete} 做「有流水就不许删账户」的判断
     * （{@code ACCOUNT_IN_USE 3024}）。
     *
     * <p><b>★ 这个检查的作用远不止"防误删"</b>：它让"流水还在、账户已被删"这个状态
     * <b>变得不可达</b>。而那个状态正是 {@link #removeByBillId} 的死角 ——
     * 账户被逻辑删除后 {@code addBalance} 匹配不到行、余额回滚不了，
     * 用户就会卡在"删不掉那笔账单"。从源头堵住比事后兜底可靠得多。
     *
     * @param userId    当前登录用户 id
     * @param accountId 账户 id
     * @return true 表示存在未删除的流水
     */
    public boolean hasActiveFlows(Long userId, Long accountId) {
        if (userId == null || accountId == null) {
            return false;
        }
        // 逻辑删除条件由 @TableLogic 自动追加
        Long count = accountFlowMapper.selectCount(
                new LambdaQueryWrapper<AccountFlow>()
                        .eq(AccountFlow::getUserId, userId)
                        .eq(AccountFlow::getAccountId, accountId));
        return count != null && count > 0;
    }

    /**
     * 分页查询某账户的资金流水，并补查转账的「对方账户名」。
     *
     * <p><b>★ 数据隔离靠 WHERE 条件，不靠"先查账户再校验"</b>：
     * 查询同时带 {@code userId} 与 {@code accountId}，所以别人的账户查出来就是空列表
     * （fail-closed）。这是刻意的 —— 本方法<b>不返回"账户不存在"错误</b>，
     * 因为对列表接口来说"空列表"与"无权限"表现一致即可，不必泄露账户是否存在。
     *
     * <p><b>执行两步</b>：
     * <ol>
     *   <li>分页查流水</li>
     *   <li>仅当本页有 TRANSFER 时，按 {@code transferNo} <b>一次</b>查出同批次的另一条流水，
     *       拿到对方账户 id → 再<b>一次</b>查出账户名。整页最多 3 次查询，与页大小无关</li>
     * </ol>
     *
     * @param userId    当前登录用户 id
     * @param accountId 账户 id（必须是当前用户的，否则返回空）
     * @param flowType  可选：只看某一类来源（BILL / TRANSFER / ADJUST）；null 表示不筛
     * @param page      页码，从 1 开始
     * @param size      每页条数（调用方应已做上限保护）
     * @return 分页结果，含 {@code total}
     */
    public Page<AccountFlowVO> pageFlows(Long userId, Long accountId, String flowType,
                                         int page, int size) {
        Page<AccountFlow> flowPage = accountFlowMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<AccountFlow>()
                        .eq(AccountFlow::getUserId, userId)
                        .eq(AccountFlow::getAccountId, accountId)
                        .eq(flowType != null && !flowType.isBlank(), AccountFlow::getFlowType, flowType)
                        // ★ 排序带 id 兜底：同一秒内的多条流水若顺序不稳，
                        //   翻页会出现重复或漏数据
                        .orderByDesc(AccountFlow::getFlowTime)
                        .orderByDesc(AccountFlow::getId));

        List<AccountFlow> records = flowPage.getRecords();
        Page<AccountFlowVO> result = new Page<>(
                flowPage.getCurrent(), flowPage.getSize(), flowPage.getTotal());
        result.setRecords(assembleFlowVOs(userId, records));
        return result;
    }

    /**
     * 把流水实体组装成 VO，并补查转账的对方账户。
     *
     * <p><b>★ 为什么要单独补对方账户</b>：一次转账产生两条流水，
     * 各记在自己那一侧（一条 OUT、一条 IN），<b>彼此只靠 {@code transferNo} 关联</b>。
     * 若不补查，用户在账户 A 的流水列表里只看到"转出 100"，不知道转给了谁。
     *
     * <p>做法：按 {@code transferNo} 一次查出本页涉及的所有转账流水
     * （会同时查出两侧），分组后取"另一侧"的 accountId，再批量查账户名。
     */
    private List<AccountFlowVO> assembleFlowVOs(Long userId, List<AccountFlow> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        // 收集本页出现的转账批次号
        Set<String> transferNos = records.stream()
                .map(AccountFlow::getTransferNo)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 一次查出这些批次的全部流水（含对方那一侧），建立 transferNo → 流水列表
        Map<String, List<AccountFlow>> byTransferNo = Map.of();
        if (!transferNos.isEmpty()) {
            List<AccountFlow> transferFlows = accountFlowMapper.selectList(
                    new LambdaQueryWrapper<AccountFlow>()
                            .eq(AccountFlow::getUserId, userId)
                            .in(AccountFlow::getTransferNo, transferNos));
            byTransferNo = transferFlows.stream()
                    .collect(Collectors.groupingBy(AccountFlow::getTransferNo));
        }

        // 建立 流水id → 对方accountId（只对 TRANSFER 有意义）
        Map<Long, Long> counterpartByFlowId = new HashMap<>();
        for (AccountFlow flow : records) {
            if (flow.getTransferNo() == null) {
                continue;
            }
            List<AccountFlow> sameBatch = byTransferNo.getOrDefault(flow.getTransferNo(), List.of());
            for (AccountFlow other : sameBatch) {
                if (!Objects.equals(other.getAccountId(), flow.getAccountId())) {
                    counterpartByFlowId.put(flow.getId(), other.getAccountId());
                    break;
                }
            }
        }

        // 一次查出所有对方账户名
        Set<Long> counterpartIds = new HashSet<>(counterpartByFlowId.values());
        Map<Long, String> accountNames = counterpartIds.isEmpty()
                ? Map.of()
                : accountMapper.selectList(new LambdaQueryWrapper<Account>()
                        .in(Account::getId, counterpartIds))
                .stream().collect(Collectors.toMap(Account::getId, Account::getName, (a, b) -> a));

        return records.stream().map(flow -> {
            Long counterpartId = counterpartByFlowId.get(flow.getId());
            return new AccountFlowVO(
                    flow.getId(),
                    flow.getFlowType(),
                    flow.getDirection(),
                    flow.getAmount(),
                    flow.getFlowTime(),
                    flow.getRemark(),
                    flow.getBillId(),
                    flow.getTransferNo(),
                    counterpartId,
                    counterpartId == null ? null : accountNames.get(counterpartId),
                    flow.getCreateTime());
        }).toList();
    }

    // ========================================================================
    // 对账自测（D3 任务 3.9）
    // ========================================================================

    /**
     * 对账：校验每个账户的余额是否等于按资金流水算出来的值。
     *
     * <p><b>★ 这是开发期提前发现所有余额 bug 的唯一手段</b>，
     * 也是 D3 把「对账接口跑通」列为当日必达的原因。
     *
     * <p>余额 bug 的特征是<b>静默</b>：流水写对了、接口返回 200，
     * 但余额少了 100。靠肉眼看接口返回值发现不了，必须用这个公式核对。
     * 典型用法是<b>每改一个方法就跑一次</b>—— 若等几个方法都写完才查，
     * 一旦不平就不知道是哪一个弄坏的。
     *
     * <p><b>执行三步</b>：
     * <ol>
     *   <li>查该用户所有未删除账户（{@code @TableLogic} 自动过滤）</li>
     *   <li><b>一次</b>查出该用户所有未删除流水（不是逐个账户查）</li>
     *   <li>在内存里按账户聚合 IN/OUT，逐账户比对</li>
     * </ol>
     *
     * @param userId 当前登录用户 id；<b>只检查该用户自己的账户</b>
     * @return 每个账户一行；用户没有账户时返回空列表（<b>不报错</b>，新用户就是这状态）
     */
    @Transactional(readOnly = true)
    public List<AccountCheckVO> reconcile(Long userId) {
        // ---------- ① 查账户 ----------
        // 排序保证输出顺序稳定，两次调用的结果可直接逐行 diff
        List<Account> accounts = accountMapper.selectList(
                new LambdaQueryWrapper<Account>()
                        .eq(Account::getUserId, userId)
                        .orderByAsc(Account::getSort)
                        .orderByAsc(Account::getId));
        if (accounts.isEmpty()) {
            return List.of();
        }

        // ---------- ② 一次查出所有流水 ----------
        // ★ 用 wrapper 而非手写 SQL：deleted = 0 由 @TableLogic 自动追加
        List<AccountFlow> flows = accountFlowMapper.selectList(
                new LambdaQueryWrapper<AccountFlow>()
                        .eq(AccountFlow::getUserId, userId));

        // ---------- ③ 内存聚合 ----------
        // merge(key, value, remapping) 省掉 containsKey 判断：
        // key 不存在时直接放入，存在时用 BigDecimal::add 合并
        Map<Long, BigDecimal> sumIn = new HashMap<>();
        Map<Long, BigDecimal> sumOut = new HashMap<>();
        for (AccountFlow flow : flows) {
            BigDecimal amount = flow.getAmount() == null ? BigDecimal.ZERO : flow.getAmount();
            if (DIRECTION_IN.equals(flow.getDirection())) {
                sumIn.merge(flow.getAccountId(), amount, BigDecimal::add);
            } else {
                // 数据库没有 CHECK 约束，理论上可能出现 IN/OUT 之外的值；
                // 这里按"非 IN 即 OUT"处理。真正的防线在 record() 的入参校验。
                sumOut.merge(flow.getAccountId(), amount, BigDecimal::add);
            }
        }

        // ---------- ④ 逐账户比对 ----------
        List<AccountCheckVO> result = new ArrayList<>(accounts.size());
        int mismatched = 0;
        for (Account account : accounts) {
            BigDecimal initBalance = account.getInitBalance() == null
                    ? BigDecimal.ZERO : account.getInitBalance();
            BigDecimal balance = account.getBalance() == null
                    ? BigDecimal.ZERO : account.getBalance();

            BigDecimal in = sumIn.getOrDefault(account.getId(), BigDecimal.ZERO);
            BigDecimal out = sumOut.getOrDefault(account.getId(), BigDecimal.ZERO);

            BigDecimal calculated = initBalance.add(in).subtract(out);
            BigDecimal diff = balance.subtract(calculated);

            // ★ 必须用 compareTo 而不是 equals：
            //   new BigDecimal("1.0").equals(new BigDecimal("1.00")) 是 false，
            //   用 equals 会把【正确的账户】误报为不平，然后你去找一个不存在的 bug
            boolean matched = diff.compareTo(BigDecimal.ZERO) == 0;
            if (!matched) {
                mismatched++;
                // 不平就记警告日志 —— 控制台里能立刻看到，不必等看到接口返回
                log.warn("对账不平：userId={}, accountId={}, name={}, balance={}, calculated={}, diff={}",
                        userId, account.getId(), account.getName(), balance, calculated, diff);
            }

            result.add(new AccountCheckVO(
                    account.getId(),
                    account.getName(),
                    balance,
                    calculated,
                    diff,
                    matched));
        }

        log.info("对账完成：userId={}, 账户数={}, 不平数={}", userId, result.size(), mismatched);
        return result;
    }
}

