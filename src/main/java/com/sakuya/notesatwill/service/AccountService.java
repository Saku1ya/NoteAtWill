package com.sakuya.notesatwill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Account;
import com.sakuya.notesatwill.entity.DTO.AccountRequest;
import com.sakuya.notesatwill.entity.DTO.AdjustRequest;
import com.sakuya.notesatwill.entity.DTO.TransferRequest;
import com.sakuya.notesatwill.entity.VO.AccountFlowVO;
import com.sakuya.notesatwill.mapper.AccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 资金账户服务。
 *
 * <p><b>作用域纪律</b>：账户归属用户，<b>所有查询都必须带 {@code userId} 条件</b>。
 * 注意账户与分类不同 —— 分类有"系统预置共享"的部分（{@code user_id IS NULL}），
 * 账户<b>没有预置数据</b>，每条都属于某个具体用户，是纯私有数据。
 *
 * <p><b>★★ 本类【不是】余额的修改入口。</b>
 * {@code t_account.balance} 只能由「资金流水服务」在写 {@code t_account_flow} 时
 * 同步维护（D2 实现）。本类只负责账户的元数据（名称、类型、图标、排序、期初余额）。
 *
 * <p>为什么这条纪律重要：{@code balance} 是冗余字段，它的正确性完全依赖
 * "每次变动都与一条资金流水同时发生"。一旦有代码绕过流水直接改余额，
 * 对账公式（{@code balance == initBalance + SUM(IN) - SUM(OUT)}）就会永久性对不上，
 * 且很难定位是哪一次操作弄坏的。
 *
 * @author sakuya
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountMapper accountMapper;

    /*
     * ★ 依赖方向：AccountService → AccountFlowService（单向，不可反向）。
     *
     * 本类负责【编排】：校验账户归属与余额是否充足、生成批次号；
     * 真正的余额变更一律委托给 AccountFlowService（它是全项目唯一能改 balance 的地方）。
     *
     * 之所以不能让 AccountFlowService 反过来注入本类：本类的 delete 要问流水服务
     * "这账户还有流水吗"，两边互相注入就成了循环依赖，Spring 启动会直接失败
     * （或被迫用 @Lazy 把问题掩盖掉）。
     */
    private final AccountFlowService accountFlowService;

    /** {@code is_default} 取值 */
    private static final int IS_DEFAULT = 1;
    private static final int NOT_DEFAULT = 0;

    /** 期初余额缺省值 */
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    // ========================================================================
    // 查询
    // ========================================================================

    /**
     * 查询当前用户的账户列表（带余额）。
     *
     * <p>排序三段式：默认账户置顶 → {@code sort} → {@code id}（稳定排序）。
     * 逻辑删除由 {@code @TableLogic} 自动过滤。
     *
     * @param userId 当前登录用户 id（来自 token）
     */
    public List<Account> list(Long userId) {
        return accountMapper.selectList(
                new LambdaQueryWrapper<Account>()
                        .eq(Account::getUserId, userId)
                        .orderByDesc(Account::getIsDefault)
                        .orderByAsc(Account::getSort)
                        .orderByAsc(Account::getId)
        );
    }

    /**
     * 按 id 查询账户，并校验归属当前用户。
     *
     * <p>与 {@code BookService.getOwned} 同样的策略：
     * <b>把 {@code userId} 写进 where 条件</b>，而不是查出来再 if 比较 ——
     * 后者漏写那段 if 就会越权，前者漏传 userId 会直接查不到（错得更明显）。
     *
     * <p>"不存在"与"不属于我"返回<b>同一个错误码</b>，避免被用来枚举账户 id。
     *
     * @param userId    当前登录用户 id
     * @param accountId 账户 id
     * @throws BusinessException 账户不存在或不属于该用户（{@code ACCOUNT_NOT_FOUND}）
     */
    public Account getOwned(Long userId, Long accountId) {
        // 入口判空：MyBatis-Plus 的 .eq(column, null) 生成 "= NULL"（恒不成立），
        // 不会误命中数据，但白跑一次查询且报错位置靠后，不如在这里直接拒绝
        if (userId == null || accountId == null) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }

        Account account = accountMapper.selectOne(
                new LambdaQueryWrapper<Account>()
                        .eq(Account::getId, accountId)
                        .eq(Account::getUserId, userId)
        );
        if (account == null) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
        return account;
    }

    // ========================================================================
    // 写操作
    // ========================================================================

    /**
     * 新建账户。
     *
     * <p><b>★ {@code balance} 必须初始化为 {@code initBalance}</b>：
     * 新建这一刻还没有任何资金流水，所以"当前余额"就等于"期初余额"。
     * 若这里忘了赋值，{@code balance} 会落到数据库的 {@code DEFAULT 0.00}，
     * 于是对账公式从第一天就对不上 —— 而用户明明填了 1000 元期初。
     *
     * <p>金额用 {@link BigDecimal}，且缺省值用字符串构造
     * （{@code new BigDecimal("0.00")} 而不是 {@code new BigDecimal(0.0)}）：
     * 后者会引入二进制浮点误差。
     *
     * <p>{@code isDefault} 固定为 0：用户不能通过接口把某账户提为默认，
     * 避免绕过相关规则（与账本同理）。
     *
     * @param userId  当前登录用户 id（来自 token，不接受前端传入）
     * @param request 账户信息
     * @return 已回填 id 的账户实体
     */
    public Account create(Long userId, AccountRequest request) {
        BigDecimal initBalance = request.initBalance() != null ? request.initBalance() : ZERO;

        Account account = new Account();
        account.setUserId(userId);
        account.setName(request.name());
        account.setType(request.type());
        account.setInitBalance(initBalance);
        // ★ 新建时当前余额 = 期初余额（此时还没有任何流水）
        account.setBalance(initBalance);
        account.setIcon(request.icon());
        account.setIsDefault(NOT_DEFAULT);
        account.setSort(request.sort() != null ? request.sort() : nextSort(userId));

        accountMapper.insert(account);
        log.info("新建账户：userId={}, accountId={}, name={}, initBalance={}",
                userId, account.getId(), account.getName(), initBalance);
        return account;
    }

    /**
     * 修改账户（只能改名称、类型、图标、排序）。
     *
     * <p><b>★★ 绝不修改 {@code balance} 与 {@code initBalance}</b> —— 这是本方法的核心约束。
     * 所以虽然 {@link AccountRequest} 里有 {@code initBalance}（供新建使用），
     * 这里也<b>必须显式忽略</b>它。
     *
     * <p>改了会怎样：
     * <ul>
     *   <li>{@code initBalance} → 对账公式 {@code balance == initBalance + ΣIN - ΣOUT}
     *       立刻失效，两边永远对不上</li>
     *   <li>{@code balance} → 账户余额与资金流水脱钩，D3 的对账自测接口永久报错</li>
     * </ul>
     * 余额若确实需要修正，唯一合法入口是「手动校正」（生成一条 ADJUST 流水），
     * 这样余额变动在流水里留下痕迹，对账公式依然成立。
     *
     * <p>{@code type} 允许修改（与分类的 {@code type} 不同）：
     * 分类改 type 会让历史账单收支反转，而账户 type 只影响前端图标显示，
     * 不参与任何统计口径，所以改它是无害的。
     *
     * @param userId    当前登录用户 id
     * @param accountId 要修改的账户 id
     * @param request   新的账户信息；{@code initBalance} 会被忽略
     * @return 修改后的账户实体
     * @throws BusinessException 账户不存在或不属于该用户
     */
    public Account update(Long userId, Long accountId, AccountRequest request) {
        // 先校验归属。getOwned 内部已抛异常，不需要再判 null
        getOwned(userId, accountId);

        Account update = new Account();
        // ★ 漏了 setId 会静默失败：updateById 按主键更新，
        //   id 为 null 时 SQL 变成 WHERE id = NULL，匹配不到任何行，
        //   接口返回 200 但库一个字没改
        update.setId(accountId);
        update.setName(request.name());
        update.setType(request.type());
        update.setIcon(request.icon());
        if (request.sort() != null) {
            update.setSort(request.sort());
        }
        // ★★ 刻意不 set：balance / initBalance / isDefault / userId
        //    （update-strategy=not_null 保证未 set 的字段不进 SQL）

        accountMapper.updateById(update);
        return getOwned(userId, accountId);
    }

    /**
     * 删除账户（逻辑删除）。
     *
     * 账户存在资金流水时应拒绝删除，抛 {@link ErrorCode#ACCOUNT_IN_USE}(3024)。
     * 现在 {@code t_account_flow} 还没有 Mapper，无法查询。
     *
     * <p>为什么这条检查必须有：资金流水是账户余额的<b>唯一依据</b>。
     * 若账户还能被查到（逻辑删除只标记 deleted=1），
     * 对账自测接口仍会遍历它；但若连账户都被过滤掉，
     * 那些流水就成了"孤儿"，对账时会被静默跳过 —— 数据看起来是干净的，
     * 实际上有账目凭空消失了。所以宁可拒绝删除，也不留下无法核对的流水。
     *
     * @param userId    当前登录用户 id
     * @param accountId 要删除的账户 id
     * @throws BusinessException 账户不存在或不属于该用户
     */
    public void delete(Long userId, Long accountId) {
        Account account = getOwned(userId, accountId);

        // ★ 有资金流水就不许删。这不只是"防误删"——
        //   它让"流水还在、账户已被删"这个状态【不可达】。
        //   而那个状态正是 AccountFlowService.removeByBillId 的死角：
        //   账户被逻辑删除后 addBalance 匹配不到行、余额回滚不了，
        //   用户会卡在"删不掉那笔账单"。从源头堵住比事后兜底可靠。
        if (accountFlowService.hasActiveFlows(userId, accountId)) {
            throw new BusinessException(ErrorCode.ACCOUNT_IN_USE);
        }

        accountMapper.deleteById(account.getId());
        log.info("删除账户：userId={}, accountId={}, name={}", userId, accountId, account.getName());
    }

    // ========================================================================
    // 转账 / 撤销转账（D4 任务 3.10 ~ 3.11）
    // ========================================================================

    /**
     * 转账：把 {@code amount} 从转出账户搬到转入账户。
     *
     * <p><b>★ 转账属于账户模块，不产生任何 {@code t_bill}</b> ——
     * 它是"账户之间搬钱"，不是消费行为。落库的只有两条
     * {@code t_account_flow}（转出 OUT + 转入 IN，共享批次号）。
     *
     * <p><b>校验顺序：先纯内存，再查库，最后查余额</b>：
     * <ol>
     *   <li>转出转入相同 → {@code ACCOUNT_SAME_TRANSFER(3022)}</li>
     *   <li>金额必须大于 0（DTO 已拦一层，这里再判一次，因为本方法可能被内部调用）</li>
     *   <li>两个账户都必须属于当前用户 → {@code ACCOUNT_NOT_FOUND(3020)}</li>
     *   <li>转出账户余额必须充足 → {@code ACCOUNT_NOT_ENOUGH_BALANCE(3021)}</li>
     * </ol>
     *
     * <p><b>⚠ 余额检查不是原子的</b>：本方法是"先读出余额 → 判断 → 再扣减"，
     * 两步之间若有并发写入，理论上可能透支。单用户手动操作不会触发，
     * 所以一期接受这个局限。若要严格，需要把条件写进 UPDATE：
     * {@code WHERE ... AND balance >= #{amount}}，影响 0 行即表示余额不足。
     *
     * <p><b>转出转入相同为什么必须拒绝</b>：那样会写出一条 OUT 和一条 IN，
     * 金额互相抵消 —— 余额没变、却留下两条毫无意义的流水，
     * 而且撤销时还要处理"两条流水指向同一个账户"这种别扭情况。
     *
     * @param userId  当前登录用户 id
     * @param request 转账信息；<b>方向由服务端决定，前端不传</b>
     * @return 转账批次号 {@code transferNo}，前端撤销时要用
     * @throws BusinessException 3022 / 4001 / 3020 / 3021
     */
    @Transactional
    public String transfer(Long userId, TransferRequest request) {
        Long fromAccountId = request.fromAccountId();
        Long toAccountId = request.toAccountId();

        // ---------- ① 纯内存校验 ----------
        // 用 Objects.equals 而不是 == ：两者都是 Long 包装类型，
        // 用 == 比较的是引用，超出 Integer 缓存范围（-128~127）就会误判为不相等
        if (Objects.equals(fromAccountId, toAccountId)) {
            throw new BusinessException(ErrorCode.ACCOUNT_SAME_TRANSFER);
        }
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BILL_AMOUNT_INVALID);
        }

        // ---------- ② 两个账户的归属 ----------
        // 转出账户这里要拿对象（后面查余额），转入账户只需要校验存在性
        Account fromAccount = getOwned(userId, fromAccountId);
        getOwned(userId, toAccountId);

        // ---------- ③ 余额充足 ----------
        if (fromAccount.getBalance().compareTo(request.amount()) < 0) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ENOUGH_BALANCE);
        }

        // ---------- ④ 生成批次号 ----------
        // ★ 去掉横线后正好 32 个字符，与 t_account_flow.transfer_no varchar(32) 严丝合缝
        //   （列宽就是按这个设计的，不是随手凑的长度）
        // ★ 必须由服务端生成：若允许前端传，两次转账可能撞号，撤销时会一次删掉两批
        String transferNo = UUID.randomUUID().toString().replace("-", "");

        // ---------- ⑤ 写两条流水（方向由流水服务内部固定：from→OUT、to→IN）----------
        accountFlowService.recordTransfer(userId, fromAccountId, toAccountId,
                request.amount(), transferNo, LocalDateTime.now(), request.remark());

        log.info("转账：userId={}, from={}, to={}, amount={}, transferNo={}",
                userId, fromAccountId, toAccountId, request.amount(), transferNo);
        return transferNo;
    }

    /*
     * ⚠ 这里【没有】cancelTransfer。
     *
     * 撤销转账做的事情只有两件：按 transferNo 作废两条流水、回滚两个账户余额 ——
     * 全是资金流水服务的职责，本类不需要做任何编排（归属过滤已在查询条件里）。
     * 所以在它上面再包一层 `cancelTransfer` 只会是个纯转发，
     * 还会让"本类不碰余额"这条纪律看起来名不副实。
     *
     * 因此 AccountController 直接调用 AccountFlowService#removeByTransferNo。
     * 判断标准：本类只保留"做了实质工作"的方法 ——
     *   · transfer   ：校验两账户归属、查余额是否充足、生成批次号
     *   · adjust     ：getOwned、算 delta、处理 delta==0 分支、定方向
     *   · listFlows  ：getOwned 校验归属
     * 纯转发不算工作。
     */

    // ========================================================================
    // 手动校正余额（D4 任务 3.13）
    // ========================================================================

    /**
     * 手动校正余额到指定值（ADJUST）。
     *
     * <p><b>★ 这是全项目唯一合法的"手工改余额"入口</b>，
     * 而且它<b>也必须写一条流水</b> —— 绝不能直接 {@code UPDATE balance}。
     * 因为余额是派生数据的缓存，正确性完全依赖"每次变动都伴随一条流水"；
     * 直接改余额会让对账公式立刻失效。
     *
     * <p><b>★ 入参是目标余额而不是调整额</b>：用户知道"应该是 1000"，
     * 但不知道"和现在差多少"。差额由服务端算，避免用户心算出错。
     *
     * <p><b>★ {@code delta == 0} 必须单独处理</b>：
     * {@code t_account_flow.amount} 的约定是"恒为正数"，而写入路径会拒绝
     * {@code amount <= 0}。若校正到与当前相同的余额还硬写一条 {@code amount=0} 的流水，
     * 用户会收到"金额必须大于 0"这种语义矛盾的错误 —— 而他只是"没改动"。
     * 正确做法是直接返回成功（余额本来就等于目标值，与对账公式也不冲突）。
     *
     * <p><b>本方法不会改 {@code init_balance}</b>：那个字段的定义是
     * "期初余额，建账户时填写，之后不变"。校正只动 {@code balance} + 记一条 ADJUST 流水，
     * 因此对账公式 {@code balance == init + SUM(IN) − SUM(OUT)} 依然成立。
     *
     * @param userId    当前登录用户 id
     * @param accountId 账户 id
     * @param request   目标余额 + 校正原因
     * @throws BusinessException 账户不存在或不属于该用户（3020）；目标余额为空（1001）
     */
    @Transactional
    public void adjust(Long userId, Long accountId, AdjustRequest request) {
        Account account = getOwned(userId, accountId);

        if (request.targetBalance() == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "目标余额不能为空");
        }

        BigDecimal delta = request.targetBalance().subtract(account.getBalance());

        // ★ delta == 0：无事发生。不报错，也不写一条金额为 0 的流水
        if (delta.compareTo(BigDecimal.ZERO) == 0) {
            log.info("校正余额：目标与当前一致，无需记录流水：userId={}, accountId={}, balance={}",
                    userId, accountId, account.getBalance());
            return;
        }

        // 余额要增加就记 IN，要减少就记 OUT；金额取绝对值（amount 恒为正）
        String direction = delta.compareTo(BigDecimal.ZERO) > 0
                ? AccountFlowService.DIRECTION_IN
                : AccountFlowService.DIRECTION_OUT;

        accountFlowService.record(userId, accountId, AccountFlowService.FLOW_TYPE_ADJUST,
                null, direction, delta.abs(), LocalDateTime.now(), request.remark());

        log.info("手动校正余额：userId={}, accountId={}, {} -> {}, delta={}",
                userId, accountId, account.getBalance(), request.targetBalance(), delta);
    }

    // ========================================================================
    // 账户资金流水列表（D4 任务 3.12）
    // ========================================================================

    /**
     * 分页查询某账户的资金流水（BILL / TRANSFER / ADJUST 三类来源）。
     *
     * <p><b>★ 先校验账户归属</b>：本方法会先 {@code getOwned}，
     * 账户不存在或不属于当前用户时返回 {@code ACCOUNT_NOT_FOUND(3020)}。
     * （{@link AccountFlowService#pageFlows} 内部的 WHERE 条件也带 userId，
     * 那是第二层保险。）
     *
     * <p><b>返回的 VO 里带"转账对方账户名"</b> —— 否则一条"转出 100"看不懂转给谁了。
     * 补查由流水服务按 {@code transferNo} 批量完成，<b>整页只多查两次，不是 N+1</b>。
     *
     * @param userId    当前登录用户 id
     * @param accountId 账户 id
     * @param flowType  可选：只看某一类来源；null 或空串表示不筛
     * @param page      页码，从 1 开始
     * @param size      每页条数
     * @throws BusinessException 账户不存在或不属于该用户（3020）
     */
    public Page<AccountFlowVO> listFlows(Long userId, Long accountId, String flowType,
                                        int page, int size) {
        getOwned(userId, accountId);
        return accountFlowService.pageFlows(userId, accountId, flowType, page, size);
    }

    // ========================================================================
    // 供注册流程调用
    // ========================================================================

    /**
     * 为用户创建默认账户（「现金」）。供注册流程调用。
     *
     * <p><b>★ 调用方必须处于事务中，本方法自身不加 {@code @Transactional}</b>：
     * 注册要同时写 {@code t_user} / {@code t_book} / {@code t_account} 三张表，
     * 任一失败必须整体回滚 —— 事务边界在 {@code AuthService.register()} 那一层。
     * 本方法只写一张表，且同类内部调用不经过 Spring 代理，
     * 即使加了注解也会被静默忽略。
     *
     * <p>期初余额给 0：新用户还不知道自己有多少钱，让他之后自己改或校正。
     * 此时 {@code balance} 也是 0，与 {@code initBalance} 一致，对账公式成立。
     *
     * @param userId 新注册用户 id
     * @return 已回填 id 的默认账户
     */
    public Account createDefaultAccount(Long userId) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName("现金");
        account.setType("CASH");
        account.setInitBalance(ZERO);
        // 新建时余额 = 期初余额，保证对账公式从一开始就成立
        account.setBalance(ZERO);
        account.setIcon("cash");
        account.setIsDefault(IS_DEFAULT);
        account.setSort(0);

        accountMapper.insert(account);
        return account;
    }

    // ========================================================================
    // 私有工具
    // ========================================================================

    /**
     * 计算下一个排序值 = 当前用户账户中最大 sort + 1。
     *
     * <p>作用域是「同一个 user_id 内」（账户没有父子层级，比分类简单）。
     */
    private int nextSort(Long userId) {
        List<Account> accounts = list(userId);
        return accounts.stream()
                .map(Account::getSort)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(-1) + 1;
    }
}
