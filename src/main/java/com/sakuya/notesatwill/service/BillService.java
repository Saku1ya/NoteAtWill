package com.sakuya.notesatwill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.common.util.MonthRange;
import com.sakuya.notesatwill.entity.Account;
import com.sakuya.notesatwill.entity.AccountFlow;
import com.sakuya.notesatwill.entity.Bill;
import com.sakuya.notesatwill.entity.Book;
import com.sakuya.notesatwill.entity.Category;
import com.sakuya.notesatwill.entity.DTO.AiBillItem;
import com.sakuya.notesatwill.entity.DTO.BillQuery;
import com.sakuya.notesatwill.entity.DTO.BillRequest;
import com.sakuya.notesatwill.entity.VO.BillSummaryVO;
import com.sakuya.notesatwill.entity.VO.BillVO;
import com.sakuya.notesatwill.mapper.AccountFlowMapper;
import com.sakuya.notesatwill.mapper.AccountMapper;
import com.sakuya.notesatwill.mapper.BillMapper;
import com.sakuya.notesatwill.mapper.BookMapper;
import com.sakuya.notesatwill.mapper.CategoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 记账服务。
 *
 * <p><b>★ 本类最重要的职责边界</b>：账单与账户是<b>两个独立功能</b>，
 * 由用户自主决定是否关联。所以新增账单时：
 * <pre>
 *   accountId == null → 只写 1 条 t_bill，不产生任何资金流水，余额纹丝不动
 *   accountId != null → 额外写 1 条 BILL 流水，该账户余额变动（同一事务）
 * </pre>
 * <b>绝不允许余额由本类直接修改</b> —— 那是 {@link AccountFlowService} 的唯一职责。
 *
 * @author sakuya
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillService {

    private final AccountFlowService accountFlowService;
    private final StatsCacheService statsCacheService;
    private final ChatMessageService chatMessageService;
    private final BookService bookService;
    private final CategoryService categoryService;
    private final BillMapper billMapper;
    /*
     * 下面四个 Mapper 只服务于「批量补查名字」（任务 3.5）。
     * 直接注入 Mapper 而不经各自的 Service，是因为这里只做"按 id 集合查名字"，
     * 不涉及任何业务规则 —— 走 Service 反而要为"批量查名字"新增方法。
     * 注意这不违反任何纪律：补查只读，不修改余额或任何业务状态。
     */
    private final AccountFlowMapper accountFlowMapper;
    private final AccountMapper accountMapper;
    private final CategoryMapper categoryMapper;
    private final BookMapper bookMapper;

    /** 账单来源：手动记账 */
    private static final String SOURCE_MANUAL = "MANUAL";
    /**
     * 账单来源：AI 解析确认。
     *
     * <p><b>★ 必须由服务端写死，绝不接受前端传入</b> ——
     * 否则任何人都能声明"我这条是 AI 记的"，污染 AI 采纳率统计。
     */
    private static final String SOURCE_AI = "AI";
    /**
     * 收支类型。★ 必须是 {@code public} ——
     * D7 的统计模块与 D8/D9 的问候卡片要调用 {@link #monthSum}，
     * 需要把类型传进来。若让它们各自写字面量 "EXPENSE"，
     * 拼错时编译不报错，只会静默统计成 0。
     */
    public static final String TYPE_EXPENSE = "EXPENSE";
    public static final String TYPE_INCOME = "INCOME";

    /**
     * 新增账单。
     * <p><b>★ 全程 {@code @Transactional}</b>：账本校验、分类校验、写账单、写流水
     * 必须在同一事务里。否则会出现"账记上了钱没扣"的脏数据 ——
     * 而且这类数据不会报错，只在对账时才暴露。
     *
     * @param userId  当前登录用户 id（来自 token）
     * @param request 账单信息；{@code accountId} 选填
     * @return 已回填 id 的账单实体
     * @throws BusinessException 金额非法（4001）/ 时间在未来（4002）/ 类型非法（4003）/
     *                           账本不存在（3000）/ 分类不存在（3010）/
     *                           分类 type 与账单 type 不一致（3015）/ 账户不存在（3020）
     */
    @Transactional
    public Bill createBill(Long userId, BillRequest request) {
        // 手动记账 = 「写一笔」的共用核心 + 来源写死 MANUAL、无 AI 日志
        //
        // ★ source 与 aiLogId 由本方法写死，不接受前端传入 ——
        //   否则任何人都能声明"我这条是 AI 记的"，污染 AI 采纳率统计。
        Bill bill = insertOneBill(
                userId,
                request.bookId(), request.categoryId(), request.type(),
                request.amount(), request.billTime(), request.remark(),
                request.accountId(),
                SOURCE_MANUAL, null);

        // ★ 统计缓存失效（第 1/4 处）
        statsCacheService.evictBook(userId, request.bookId());

        // ★★ 写一张账单卡片到对话流（与记账同一事务），并在提交后 SSE 推送
        //    卡片写失败不会回滚记账 —— 详见 ChatMessageService 的说明
        chatMessageService.appendBillCard(userId, bill, false);
        return bill;
    }

    /**
     * <b>「写一笔账单」的共用核心</b> —— 校验 + 写 {@code t_bill} + 可选写资金流水。
     *
     * <p><b>★ 为什么要有这个方法</b>：手动记账（{@link #createBill}）与
     * AI 确认（{@link #confirmAiBills}）在"写一笔"这件事上<b>完全一样</b>：
     * 校验字段、插账单、选了账户就记流水。差别只在
     * {@code source}（MANUAL / AI）和 {@code aiLogId}（null / 有值）。
     *
     * <p><b>★ 但"整个业务动作"不复用</b>：
     * 手动是单笔；AI 是 N 笔 + 更新解析日志状态，事务边界不同。
     * <b>共用的是"写一笔"的原子操作，不是"整个业务动作"。</b>
     *
     * <p><b>★★ {@code billTime} 必须非空</b>（由调用方归一化后再传）：
     * 这是设计 Y 的直接后果 —— 归一化的责任落在各自的入口。
     * <ul>
     *   <li>手动路径：{@code BillRequest.billTime} 是必填，缺了在校验里就抛 4005</li>
     *   <li>AI 路径：{@link #confirmAiBills} 在调用前把 null 兜底成 {@code now()}</li>
     * </ul>
     * 于是本方法内部<b>不必再判空</b>，逻辑保持干净。
     *
     * @param source  账单来源，由调用方写死（MANUAL / AI）
     * @param aiLogId AI 解析记录 id；手动记账传 {@code null}
     */
    private Bill insertOneBill(Long userId,
                               Long bookId, Long categoryId, String type,
                               BigDecimal amount, LocalDateTime billTime, String remark,
                               Long accountId,
                               String source, Long aiLogId) {

        // ---------- 校验（与修改账单共用同一套规则）----------
        validateBillInput(userId, bookId, categoryId, type, amount, billTime);

        // ---------- 写账单 ----------
        Bill bill = new Bill();
        bill.setUserId(userId);
        bill.setBookId(bookId);
        bill.setCategoryId(categoryId);
        bill.setType(type);
        bill.setAmount(amount);
        bill.setRemark(remark);
        bill.setBillTime(billTime);
        bill.setSource(source);
        // aiLogId 只在 AI 路径有值，用于追溯"这笔账是哪次解析来的"
        bill.setAiLogId(aiLogId);

        billMapper.insert(bill);

        // ---------- 仅当用户【选了】账户，才产生资金流水 ----------
        // ★★ 这是本方法最关键的一个分支，也是"记账与账户是两个独立功能"的落点。
        //    写进 if 里面，不要提到外面 —— 一旦每笔账单都写流水，
        //    就等于把两个功能重新绑死，与产品设计相反。
        if (accountId != null) {
            // 方向由账单 type 推导。★ 走 directionOf 而不是在这里写字面量 ——
            // 修改账单也要用同一个推导，两处各写一份迟早会不一致。
            String direction = directionOf(type);

            // 账户的归属与存在性不需要在这里另查一遍：
            // record() 内部通过 addBalance 的返回值（影响行数）已经完成校验，
            // 返回 0 时会抛 ACCOUNT_NOT_FOUND 并让整个事务回滚。
            accountFlowService.record(
                    userId,
                    accountId,
                    AccountFlowService.FLOW_TYPE_BILL,
                    bill.getId(),
                    direction,
                    amount,
                    billTime,   // ★ 与 bill.billTime 严格一致
                    null        // 备注留空；流水的时间与账单一致，无需重复备注
            );

            log.info("写账单（含资金流水）：userId={}, billId={}, accountId={}, direction={}, amount={}, source={}",
                    userId, bill.getId(), accountId, direction, amount, source);
        } else {
            // 没选账户：纯消费记录，不碰任何账户余额
            log.info("写账单（未关联账户）：userId={}, billId={}, amount={}, source={}",
                    userId, bill.getId(), amount, source);
        }

        // insert 后 id 已由 MP 回填，直接返回，不必再查一次
        return bill;
    }

    /**
     * <b>AI 确认：把用户核对过的草稿批量落库</b>。
     *
     * <p><b>★ 为什么必须与 {@link #createBill} 分开，而不能加个 source 参数复用</b>：
     * <table border="1">
     *   <caption>两条路径的差异</caption>
     *   <tr><th>差异点</th><th>手动</th><th>AI</th></tr>
     *   <tr><td>落库条数</td><td>1 条</td><td><b>N 条</b></td></tr>
     *   <tr><td>{@code source}</td><td>MANUAL</td><td>AI（写死，不接受传入）</td></tr>
     *   <tr><td>{@code ai_log_id}</td><td>null</td><td>回填</td></tr>
     *   <tr><td>日志联动</td><td>无</td><td>更新 {@code t_ai_parse_log} 状态</td></tr>
     *   <tr><td>事务范围</td><td>单笔</td><td><b>N 笔 + 日志状态必须整体成功或整体回滚</b></td></tr>
     * </table>
     *
     * <p><b>★ 最后一条最要紧</b>：若第 3 笔的分类非法导致失败，
     * 前 2 笔也必须回滚、日志状态不能改成 CONFIRMED，
     * 否则用户会看到"日志说已确认，但账单只落了一半"。
     * 本方法是 {@code @Transactional}，调用方 {@code AiService.confirm} 也是，
     * 于是日志更新会加入同一事务。
     *
     * <p><b>★ {@code billTime} 的归一化在这里完成</b>（设计 Y 的三层约定的第三层）：
     * <pre>
     *   Prompt  → 要求"没提时间填当前时刻"（模型未必听话）
     *   Python  → 收到 null 兜底（第一层防线）
     *   Java    → 这里再兜一次（最后防线：t_bill.bill_time 是 NOT NULL，null 会报 SQL 错误）
     * </pre>
     * <b>必须在调用 {@code insertOneBill} 之前</b>完成 —— 那个方法假定 billTime 非空。
     *
     * @param userId  当前登录用户 id
     * @param logId   解析记录 id
     * @param bookId  账单落入的账本（必须属于该用户）
     * @param bills   用户确认后的账单列表
     * @return 落库后的账单实体（含回填 id）
     * @throws BusinessException 任一字段非法（字段级错误码与手动记账一致）
     */
    @Transactional
    public List<Bill> confirmAiBills(Long userId, Long logId, Long bookId, List<AiBillItem> bills) {
        if (bills == null || bills.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "至少需要确认一笔账单");
        }

        List<Bill> saved = new ArrayList<>(bills.size());
        for (AiBillItem item : bills) {
            // ★ 归一化：模型/前端都可能留下 null，这里是最后一道防线
            LocalDateTime billTime = item.billTime() != null ? item.billTime() : LocalDateTime.now();

            saved.add(insertOneBill(
                    userId,
                    bookId, item.categoryId(), item.type(),
                    item.amount(), billTime, item.remark(),
                    item.accountId(),
                    SOURCE_AI, logId));
        }

        log.info("AI 批量落库：userId={}, logId={}, bookId={}, 笔数={}",
                userId, logId, bookId, saved.size());

        // ★ 统计缓存失效（第 2/4 处）—— 一次批量操作只清一次，不要放进循环
        statsCacheService.evictBook(userId, bookId);

        // ★★ 每笔都写一张账单卡片（AI 来源），提交后统一推送
        //    循环在事务内，卡片与账单同生共死
        for (Bill bill : saved) {
            chatMessageService.appendBillCard(userId, bill, true);
        }
        return saved;
    }

    /**
     * 修改账单（<b>整体替换语义</b>）。
     *
     * <p><b>核心难点是「账户三态切换」</b>，三种情况都与账单更新同事务：
     * <table border="1">
     *   <caption>账户字段的三种变更</caption>
     *   <tr><th>变更</th><th>要做什么</th></tr>
     *   <tr><td>无 → 有</td><td>补建一条 BILL 流水（余额变动）</td></tr>
     *   <tr><td>有 → 无</td><td>删掉原流水并把余额<b>回滚</b></td></tr>
     *   <tr><td>有 → 有（换账户）</td><td>删旧流水回滚 + 建新流水扣减，<b>两次余额变动</b></td></tr>
     * </table>
     *
     * <p><b>实现方式是「全删重建」</b>：先 {@code removeByBillId} 一次性回滚旧流水，
     * 再用 {@code LambdaUpdateWrapper} 显式 set 所有字段，最后按新参数写新流水。
     * 这样三种情况走同一条代码路径，不必分别处理。
     * <b>用 {@code LambdaUpdateWrapper} 而不是 {@code updateById}</b>：
     * 后者受 {@code update-strategy: not_null} 影响会跳过 null 字段（清空备注会静默失败），
     * 全 null 时甚至生成非法 SQL。
     *
     * <p><b>三种情况最容易漏的是"有 → 无"</b>：删了流水却忘了把余额加回去，
     * 结果钱永久消失，而且对账接口会立刻报错。
     *
     * <p>另外：若修改了 {@code type}（支出↔收入），流水的 {@code direction}
     * 也必须跟着反转，否则余额方向与账单语义相反 —— 由 {@code directionOf} 统一推导。
     *
     * <p>⚠ <b>{@code accountId} 必须显式传</b>（id = 关联，null = 有意解除）；
     * 若传 null 但实际回滚了流水，会打一条 WARN 日志让"前端少传"可见。
     *
     * @param userId  当前登录用户 id
     * @param billId  要修改的账单 id
     * @param request 新的账单信息
     * @return 修改后的账单实体
     */
    @Transactional
    public Bill updateBill(Long userId, Long billId, BillRequest request) {


        Bill original = getOwnedBill(userId, billId);
        // ★ 记下原账本 id：若这次把账单挪到别的账本，新旧两个账本的统计都变了，
        //   缓存必须两边都清（只清新账本会留下旧账本的过期数据）。
        Long originalBookId = original.getBookId();

        validateBillInput(userId, request.bookId(), request.categoryId(),
                request.type(), request.amount(), request.billTime());

        int rolledBack = accountFlowService.removeByBillId(userId, billId);


        billMapper.update(null, new LambdaUpdateWrapper<Bill>()
                .eq(Bill::getId, billId)
                .eq(Bill::getUserId, userId)
                .set(Bill::getBookId, request.bookId())
                .set(Bill::getCategoryId, request.categoryId())
                .set(Bill::getType, request.type())
                .set(Bill::getAmount, request.amount())
                .set(Bill::getRemark, request.remark())
                .set(Bill::getBillTime, request.billTime()));

        // 判断依据必须是 request.accountId()，而不是"原来有没有账户"：
        //   原来有 + 现在传了   → 重建（可能换了账户/改了金额）
        //   原来有 + 现在传 null → 不重建（用户主动取消账户关联）
        //   原来无 + 现在传了   → 重建（用户新增账户关联）
        if (request.accountId() != null) {
            accountFlowService.record(
                    userId,
                    request.accountId(),
                    AccountFlowService.FLOW_TYPE_BILL,
                    billId,
                    directionOf(request.type()),
                    request.amount(),
                    request.billTime(),   // ★ 与 t_bill.bill_time 严格一致
                    null);
        } else if (rolledBack > 0) {
            // 原本有账户关联、这次却没传 accountId。
            log.warn("修改账单时 accountId 为空但原本存在账户关联，已按『取消账户』处理："
                            + "userId={}, billId={}。若用户并未主动取消，"
                            + "说明前端漏传了 accountId（PUT 应整体提交，见接口文档）",
                    userId, billId);
        }

        log.info("修改账单：userId={}, billId={}, accountId={}, 回滚流水={} 条",
                userId, billId, request.accountId(), rolledBack);

        // ★ 统计缓存失效（第 3/4 处）
        //   ★ 用 request.bookId() 而不是原账本 id：若这次改了账本，
        //     【新旧两个账本】的统计都受影响。这里先清新账本，下面再清旧账本。
        statsCacheService.evictBook(userId, request.bookId());
        if (!Objects.equals(originalBookId, request.bookId())) {
            statsCacheService.evictBook(userId, originalBookId);
        }

        // ---------- ⑥ 返回更新后的账单 ----------
        // 重新查一次，这样返回的 update_time 是数据库刚写入的新值
        return billMapper.selectById(billId);
    }

    /**
     * 删除账单。
     *
     * <p><b>★ 全程 {@code @Transactional}</b>：本方法要写两处（余额/流水 + 账单），
     * 少了事务就会出现"账单删了、钱没还回去"的脏数据 ——
     * 而且这种数据不会报错，只在对账时才暴露。
     *
     * <p>同一事务内三步：
     * <ol>
     *   <li>校验账单归属（不存在或不属于我 → 4000，此时不产生任何写入）</li>
     *   <li><b>先</b>撤销资金影响：删关联的 BILL 流水 + 回滚余额
     *       （由 {@link AccountFlowService#removeByBillId} 承担；
     *       该账单没有流水时它静默返回 0）</li>
     *   <li><b>再</b>逻辑删除账单本身（{@code @TableLogic} 自动转成 {@code UPDATE deleted=1}）</li>
     * </ol>
     *
     * <p><b>★ 第 2 步必须在第 3 步之前</b>：先撤销影响、再抹掉记录，
     * 这样即使回滚失败（例如账户已被删除），账单也还留在库里 ——
     * 用户能看到它、也有机会补救，而不是"记录没了、余额还是错的"。
     *
     * <p><b>★★ "没选账户的账单"是本方法最重要的分支</b>：
     * 用户记账时账户是选填的，<b>没选账户的账单在流水表里根本没有记录</b>，
     * 所以第 2 步应当<b>什么都不做</b>。
     * 若这里漏了判断、拿默认值去"回滚"，就会去改<b>别的账户</b>的余额 ——
     * 这正是手册点名的坑。
     * （安全网：{@code addBalance} 的 SQL 带 {@code id + user_id + deleted=0}
     * 三重条件，匹配不到时返回 0，{@code removeByBillId} 会抛异常而不是静默走偏。）
     *
     * <p><b>为什么直接复用 {@code getOwnedBill} 而不自己 {@code selectOne}</b>：
     * {@code selectOne} 查不到时返回 {@code null}，若继续往下走，
     * {@code deleteById(null)} 拿不到 id（MyBatis-Plus 需要从实体里取主键），
     * 结果是抛一个不明的运行时异常（500），而不是干净的 {@code BILL_NOT_FOUND(4000)}。
     * {@code getOwnedBill} 已经把"不存在"与"不属于我"合并成同一个 4000，且已被测试覆盖。
     *
     * <p><b>D9 会在此基础上加"同时删除账单卡片消息"</b>（双向级联删除），
     * 本期<b>不碰</b> {@code t_chat_message}。
     *
     * @param userId 当前登录用户 id
     * @param billId 要删除的账单 id
     * @throws BusinessException 账单不存在或不属于当前用户（4000）；
     *                           或其流水指向的账户已不可用（3020，此时整体回滚）
     */
    @Transactional
    public void deleteBill(Long userId, Long billId) {
        // ① 账单归属（内部已含 userId/billId 的 null 判断，并抛 4000）
        Bill bill = getOwnedBill(userId, billId);

        // ② 先撤销资金影响：删 BILL 流水 + 回滚余额；没有流水时静默返回 0
        int rolledBack = accountFlowService.removeByBillId(userId, billId);

        // ③ 逻辑删除账单本身
        //    ★ 用 deleteById(billId) 而不是 deleteById(实体)：
        //      只需主键，语义更直接，也不存在"实体为 null"的可能。
        billMapper.deleteById(billId);

        // ④ ★★ 双向级联删除的方向一：删账单 → 卡片一起消失
        //    ★ 必须在【同一事务】里：账单删了但卡片还在，对话流里就会出现
        //      一张点进去 404 的死卡片。
        //    ★ 卡片删除失败不应该阻止账单删除（账单是主数据），
        //      所以 removeByBillId 内部吞掉异常 —— 但正常情况它会成功。
        chatMessageService.removeByBillId(userId, billId);

        // ★ 统计缓存失效（第 4/4 处）
        statsCacheService.evictBook(userId, bill.getBookId());

        log.info("删除账单：userId={}, billId={}, 回滚流水={} 条", userId, billId, rolledBack);
    }

    /**
     * 校验账单入参 —— <b>新增与修改共用</b>。
     *
     * <p><b>★ 为什么必须共用</b>：新增和修改要拒绝的是同一批非法输入。
     * 若两处各写一份，迟早会演化成"新增时拒绝、修改时放行"的漏洞 ——
     * 攻击者只要用修改接口就能绕过新增接口的校验。
     * 这类漏洞不会报错，只会在数据里留下非法记录。
     *
     * <p><b>校验顺序：先纯内存判断，再查库。</b>
     * 非法请求在碰到数据库之前就被拒绝，不浪费查询。
     *
     * <p><b>关于"DTO 上已有校验，这里为何再判一次"</b>：
     * DTO 的注解只在 Controller 的 {@code @Valid} 生效；
     * 本方法若被其它 Service 内部调用（将来 D6 的 AI 确认路径就是）就绕过了那层。
     * 所以 Service 的完整性<b>不依赖调用方</b>。
     *
     * <p><b>注意本方法不校验"账单归属"</b>（那需要 billId，只有修改/删除才有）——
     * 修改时请先调用 {@link #getOwnedBill}。
     *
     * <p><b>★ 为什么用散参而不是 {@code BillRequest}</b>：
     * AI 确认路径（{@link #confirmAiBills}）用的是 {@code AiBillItem}，不是 {@code BillRequest}
     * （设计 Y：两条路径各自建 DTO）。若本方法只接受 {@code BillRequest}，
     * AI 路径就只能重新拼一个假的 {@code BillRequest} 才能校验 —— 那是为了迁就签名而造数据。
     * 改成散参后两条路径都能直接复用同一套校验规则。
     *
     * <p><b>参数 {@code billTime} 必须非空</b>：调用方负责归一化
     * （手动路径由 DTO 的 {@code @NotNull} 拦，AI 路径由 {@code confirmAiBills} 兜底）。
     *
     * @param userId     当前登录用户 id
     * @param bookId     账本 id
     * @param categoryId 分类 id
     * @param type       收支类型
     * @param amount     金额
     * @param billTime   记账时间，<b>必须非空</b>
     * @throws BusinessException 金额非法（4001）/ 类型非法（4003）/
     *                           时间缺失（4005）或晚于当前（4002）/
     *                           账本不存在（3000）/ 分类不存在（3010）/
     *                           分类 type 与账单 type 不一致（3015）
     */
    private void validateBillInput(Long userId, Long bookId, Long categoryId,
                                   String type, BigDecimal amount, LocalDateTime billTime) {
        // ① 金额必须大于 0（金额恒为正数，方向由 type 表达）
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BILL_AMOUNT_INVALID);
        }

        // ② type 只有两种取值。转账【不在】本表 ——
        //    转账是账户模块的操作，不是消费行为。
        if (!TYPE_EXPENSE.equals(type) && !TYPE_INCOME.equals(type)) {
            throw new BusinessException(ErrorCode.BILL_TYPE_INVALID);
        }

        // ③ 记账时间：必填（设计 Y），且不能晚于当前时间
        if (billTime == null) {
            throw new BusinessException(ErrorCode.BILL_TIME_REQUIRED);
        }
        // 留 1 分钟容差 —— 客户端时钟可能比服务器快几秒，不该因此拒绝
        if (billTime.isAfter(LocalDateTime.now().plusMinutes(1))) {
            throw new BusinessException(ErrorCode.BILL_TIME_FUTURE);
        }

        // ④ 账本归属。getOwned 内部已做"存在性 + 属于当前用户"的双重校验并抛 3000，
        //    所以这里不需要判返回值是否为 null。
        bookService.getOwned(userId, bookId);

        // ⑤ 分类存在 + 可见 + type 一致。
        //    getVisible 复用 tree() 的可见性口径（系统预置 或 我自建），
        //    ★ 这同时是"防分类幻觉"的最终防线：模型编造的 id 会在这里被拒
        //      （AI 解析阶段已过滤过一次，但前端提交的草稿仍可能是任意值）。
        Category category = categoryService.getVisible(userId, categoryId);

        // ★ 跨字段约束：分类的 type 必须与账单的 type 一致。
        //   数据库层面【没有】这个约束（无外键、无 CHECK），只能靠 Service。
        //   若允许不一致，就会出现"一笔支出用了收入分类"的自相矛盾数据，
        //   按分类聚合统计时会出现方向性错误。
        if (!Objects.equals(category.getType(), type)) {
            throw new BusinessException(ErrorCode.CATEGORY_TYPE_MISMATCH);
        }
    }

    /**
     * 由账单的 type 推导资金流水方向。
     *
     * <p><b>★ 这一行写反了，余额会朝反方向跑，而且对账接口查不出来</b> ——
     * 因为流水与余额是"一致地错"：流水记 OUT、余额也按 OUT 扣，两者自洽，
     * 只有业务语义错了（一笔支出让余额变多）。
     *
     * <p>所以新增与修改都必须走这个方法，<b>不允许各写一份字面量</b>。
     *
     * @param type EXPENSE / INCOME
     * @return EXPENSE → OUT（钱流出账户）；INCOME → IN（钱流入账户）
     */
    private String directionOf(String type) {
        return TYPE_EXPENSE.equals(type)
                ? AccountFlowService.DIRECTION_OUT
                : AccountFlowService.DIRECTION_IN;
    }

    // ========================================================================
    // 列表页汇总（任务 3.8）与共用的月度聚合口径
    // ========================================================================

    /**
     * 列表页顶部汇总：本月收入 / 本月支出 / 结余。
     *
     * <p><b>口径</b>（设计文档已定死，勿改）：<b>当前账本 + 当前自然月</b>。
     * 时间范围是 {@code [本月 1 日 00:00, 次月 1 日 00:00)}（左闭右开）。
     * <b>不跟随分类 / 账户 / 关键词筛选</b> —— 卡片回答的是
     * "这个账本这个月花了多少"，与用户在筛选栏里选了什么无关。
     *
     * <p>只读，不需要 {@code @Transactional}。
     *
     * @param userId 当前登录用户 id
     * @param bookId 账本 id；<b>必须属于当前用户</b>
     * @return 汇总；无数据的月份收入/支出为 {@code 0}，结余可能为负
     * @throws BusinessException 账本不存在或不属于该用户（{@code BOOK_NOT_FOUND}）
     */
    public BillSummaryVO summary(Long userId, Long bookId) {
        // 校验账本归属（内部已处理"不存在"与"不属于我"，都抛 3000）
        Book book = bookService.getOwned(userId, bookId);

        YearMonth month = YearMonth.now();
        BigDecimal income = monthSum(userId, bookId, TYPE_INCOME, month);
        BigDecimal expense = monthSum(userId, bookId, TYPE_EXPENSE, month);

        log.debug("列表页汇总：userId={}, bookId={}, month={}, income={}, expense={}",
                userId, bookId, month, income, expense);

        return new BillSummaryVO(
                bookId,
                book.getName(),
                month.toString(),      // yyyy-MM
                income,
                expense,
                income.subtract(expense));
    }

    /**
     * <b>月度金额聚合 —— 全项目唯一的「本月支出/收入」口径实现。</b>
     *
     * <p><b>★★ 这个方法必须被共用，不许在别处复制 SQL。</b>
     * 设计文档明确要求：
     * <blockquote>
     * 口径实现为一个独立方法，供统计模块与问候卡片共用，避免两处算法不一致。
     * </blockquote>
     * 它的调用方至少有三个：
     * <ol>
     *   <li>本类的 {@link #summary}（账单列表页顶部汇总）</li>
     *   <li>D7 的统计模块 {@code /api/stats/summary}（区间总收支、日均）</li>
     *   <li>D8/D9 的每日问候卡片（"本月支出 / 日均支出"）</li>
     * </ol>
     * <b>一旦有人复制了这份 SQL，同一个页面就会开始出现两个不同的数字</b> ——
     * 而且因为两处都"看起来对"，排查起来极难。
     *
     * <p>时间区间按<b>左闭右开</b>构造：{@code [本月 1 日 00:00, 次月 1 日 00:00)}。
     * 用"次月 1 日"作终点而不是"本月最后一刻"，就不必纠结月末是
     * {@code 23:59:59} 还是 {@code 23:59:59.999}。
     *
     * @param userId 当前登录用户 id
     * @param bookId 账本 id
     * @param type   收支类型，用 {@link #TYPE_EXPENSE} / {@link #TYPE_INCOME}
     * @param month  统计月份
     * @return 金额合计；<b>无数据时为 {@code 0}</b>（底层已 COALESCE，此处再兜一层防 null）
     */
    public BigDecimal monthSum(Long userId, Long bookId, String type, YearMonth month) {
        // ★ 月份区间统一由 MonthRange 推导（左闭右开），
        //   禁止在这里手写 atDay(1) / plusMonths(1)，
        //   也禁止别处再算一遍 —— 预算与统计必须共用同一口径。
        MonthRange range = MonthRange.of(month);
        BigDecimal sum = billMapper.sumAmount(userId, bookId, type, range.start(), range.end());
        // 底层 SQL 已 COALESCE(...,0)，理论上不会为 null；
        // 这里再兜一层，是因为"统计数字返回 null"会让前端直接崩，代价远大于一次判空
        return sum == null ? BigDecimal.ZERO : sum;
    }

    // ========================================================================
    // 查询（任务 3.4 + 3.5）
    // ========================================================================

    /**
     * 分页查询账单（多条件筛选 + 名字补查）。
     *
     * <p><b>整个方法不需要 {@code @Transactional}</b>：它只读不写，
     * 不存在跨表一致性问题。加事务反而会占着连接做无谓的开销。
     *
     * <p><b>执行分两步</b>：
     * <ol>
     *   <li><b>分页查询</b>（任务 3.4）：按条件筛出当页账单。
     *       注意"按账户筛"要跨表，见下面 {@code accountId} 的处理</li>
     *   <li><b>批量补查名字</b>（任务 3.5）：把当页账单涉及的分类/账本/账户
     *       各用<b>一次</b>查询查出名字，在内存里填进 VO。
     *       <b>整页共 5 次查询，与页大小无关</b>（账单 + 流水 + 账户 + 分类 + 账本）</li>
     * </ol>
     *
     * <p><b>★ 按账户筛为什么要走子查询，而不是 JOIN</b>：
     * {@code t_bill} 里没有 {@code account_id}，只能通过 {@code t_account_flow} 关联。
     * <pre>
     *   ✅ IN (子查询)：WHERE id IN (SELECT bill_id FROM t_account_flow WHERE ...)
     *      → 一笔账单只出现一次；total 精确
     *
     *   ❌ JOIN：SELECT ... FROM t_bill b JOIN t_account_flow f ON ...
     *      → 若一笔账单有多条流水（数据异常时可能），该账单会出现多行，
     *        且 COUNT 会把 total 算成重复行数 —— 分页数字全错
     * </pre>
     * 已实测验证：一笔账单挂 2 条流水时，JOIN 让 total=2，子查询仍为 1。
     *
     * <p><b>★ 子查询里必须手写 {@code deleted = 0}</b>：
     * {@code @TableLogic} 只对 MyBatis-Plus <b>自动生成</b>的 SQL 生效，
     * 对 {@code inSql} 里的手写 SQL <b>不生效</b>。漏了它，
     * 已逻辑删除的流水仍会参与筛选。
     *
     * <p><b>★ 时间区间用左闭右开</b>：{@code >= startTime AND < endTime}。
     * 用 {@code <= endTime} 会漏掉 {@code 23:59:59.500} 这类带毫秒的数据。
     *
     * @param userId 当前登录用户 id（来自 token），<b>强制加入条件</b>
     * @param query  筛选条件（全部可选）+ 分页参数
     * @return 带 total 的分页结果；无数据时 records 为空列表而不是 null
     */
    public Page<BillVO> pageBills(Long userId, BillQuery query) {
        // ---------- 第一步：分页查询 ----------
        LambdaQueryWrapper<Bill> wrapper = new LambdaQueryWrapper<Bill>()
                // ★ user_id 是硬条件，放最前面：这是数据隔离的根本
                .eq(Bill::getUserId, userId)
                // 以下条件用"带 boolean 重载"按需拼接：
                // 第一个参数为 false 时该条件被整个跳过，无需写一堆 if
                .eq(query.bookId() != null, Bill::getBookId, query.bookId())
                .eq(query.type() != null, Bill::getType, query.type())
                .eq(query.categoryId() != null, Bill::getCategoryId, query.categoryId())
                // 时间：左闭右开
                .ge(query.startTime() != null, Bill::getBillTime, query.startTime())
                .lt(query.endTime() != null, Bill::getBillTime, query.endTime())
                .like(query.keyword() != null && !query.keyword().isBlank(),
                        Bill::getRemark, query.keyword())
                // 排序：时间倒序 + id 倒序（tie-breaker，保证分页顺序稳定。
                // 少了它，同一秒内录入的多笔账单在翻页时可能重复或漏掉）
                .orderByDesc(Bill::getBillTime)
                .orderByDesc(Bill::getId);

        // ★ 按账户筛：跨表，用子查询
        if (query.accountId() != null) {
            wrapper.inSql(Bill::getId,
                    "SELECT bill_id FROM t_account_flow"
                            + " WHERE account_id = " + query.accountId()
                            + " AND flow_type = 'BILL'"
                            // 手写 SQL 必须自己带 deleted 条件（@TableLogic 管不到这里）
                            + " AND deleted = 0");
        }

        Page<Bill> billPage = billMapper.selectPage(
                Page.of(query.safePage(), query.safeSize()), wrapper);

        // ---------- 第二步：批量补查 + 组装 ----------
        List<BillVO> records = assembleVOs(billPage.getRecords());

        // ★ Page<Bill> 不能直接当 Page<BillVO> 用（泛型不变）。
        //   必须新建一个 Page，把 total/current/size 搬过去 ——
        //   漏了 total 前端就没法渲染分页控件。
        Page<BillVO> result = new Page<>(
                billPage.getCurrent(), billPage.getSize(), billPage.getTotal());
        result.setRecords(records);
        return result;
    }

    /**
     * 查询单条账单详情（同样要补查名字）。
     *
     * @param userId 当前登录用户 id
     * @param billId 账单 id
     * @throws BusinessException 账单不存在或不属于该用户（{@code BILL_NOT_FOUND}）
     */
    public BillVO getBill(Long userId, Long billId) {
        Bill bill = getOwnedBill(userId, billId);
        // 单条也走同一个组装方法，保证列表与详情的字段口径完全一致
        return assembleVOs(List.of(bill)).get(0);
    }

    /**
     * 按 id 查账单并校验归属。
     *
     * <p>把 {@code userId} 写进 where 条件，而不是查出来再 if 比较 ——
     * 与 {@code BookService.getOwned} 同一策略：
     * 前者漏传 userId 会直接查不到（错得明显），后者漏写 if 就会越权（错得隐蔽）。
     *
     * <p>"不存在"与"不属于我"返回<b>同一个错误码</b>，避免被用来枚举账单 id。
     *
     * @throws BusinessException 账单不存在或不属于该用户
     */
    public Bill getOwnedBill(Long userId, Long billId) {
        if (userId == null || billId == null) {
            throw new BusinessException(ErrorCode.BILL_NOT_FOUND);
        }
        Bill bill = billMapper.selectOne(
                new LambdaQueryWrapper<Bill>()
                        .eq(Bill::getId, billId)
                        .eq(Bill::getUserId, userId)
        );
        if (bill == null) {
            throw new BusinessException(ErrorCode.BILL_NOT_FOUND);
        }
        return bill;
    }

    /**
     * 把账单实体列表组装成 VO 列表（<b>批量补查名字，避免 N+1</b>）。
     *
     * <p><b>★ 为什么必须"批量"而不是逐条查</b>：
     * <pre>
     *   ❌ 逐条查：20 条账单 × 3 类名字 = 60 次查询，加上账单本身共 61 次
     *   ✅ 批量查：账单 1 次 + 流水 1 次 + 账户 1 次 + 分类 1 次 + 账本 1 次 = 5 次
     * </pre>
     * 差别不随页大小增长 —— 这就是"整页一次查询"的含义。
     *
     * <p><b>四步</b>：
     * <ol>
     *   <li>收集当页所有 billId，<b>一次</b>查流水 → {@code Map<billId, accountId>}</li>
     *   <li>收集所有 accountId / categoryId / bookId</li>
     *   <li>三类名字各<b>一次</b>查出 → 三个 {@code Map<id, name>}</li>
     *   <li>遍历账单填进 VO</li>
     * </ol>
     *
     * @param bills 当页账单实体（可为空列表）
     * @return VO 列表，顺序与入参一致
     */
    private List<BillVO> assembleVOs(List<Bill> bills) {
        if (bills == null || bills.isEmpty()) {
            return List.of();
        }

        // ---------- ① 一次查流水：拿到 billId → accountId 的映射 ----------
        // 只需 BILL 类型的流水（转账/校正的 bill_id 为 null，且不属于账单）
        List<Long> billIds = bills.stream().map(Bill::getId).toList();
        List<AccountFlow> flows = accountFlowMapper.selectList(
                new LambdaQueryWrapper<AccountFlow>()
                        .in(AccountFlow::getBillId, billIds)
                        .eq(AccountFlow::getFlowType, AccountFlowService.FLOW_TYPE_BILL)
        );
        // 一笔账单最多一条 BILL 流水（Service 层保证），但数据异常时可能多条。
        // 用 toMap 且不加 merge 函数会抛 IllegalStateException，
        // 所以显式取"第一条"，让异常数据不至于让整个列表接口 500。
        Map<Long, Long> accountIdByBillId = new HashMap<>();
        for (AccountFlow flow : flows) {
            accountIdByBillId.putIfAbsent(flow.getBillId(), flow.getAccountId());
        }

        // ---------- ② 收集三类别 id ----------
        Set<Long> accountIds = new HashSet<>(accountIdByBillId.values());
        accountIds.remove(null);
        Set<Long> categoryIds = bills.stream().map(Bill::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> bookIds = bills.stream().map(Bill::getBookId)
                .filter(Objects::nonNull).collect(Collectors.toSet());

        // ---------- ③ 三类名字各查一次 ----------
        Map<Long, String> accountNames = accountIds.isEmpty()
                ? Map.of()
                : accountMapper.selectList(new LambdaQueryWrapper<Account>()
                        .in(Account::getId, accountIds))
                .stream().collect(Collectors.toMap(Account::getId, Account::getName, (a, b) -> a));

        // ★ 注意：@TableLogic 会自动过滤已删除的分类，
        //   所以被删除的分类查不到名字 —— 这里刻意不特殊处理，
        //   让它显示为 null，前端标注"已删除"即可。
        //   若将来要显示原名，需要额外查一次包含已删除数据的 SQL。
        Map<Long, String> categoryNames = categoryIds.isEmpty()
                ? Map.of()
                : categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                        .in(Category::getId, categoryIds))
                .stream().collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));

        Map<Long, String> bookNames = bookIds.isEmpty()
                ? Map.of()
                : bookMapper.selectList(new LambdaQueryWrapper<Book>()
                        .in(Book::getId, bookIds))
                .stream().collect(Collectors.toMap(Book::getId, Book::getName, (a, b) -> a));

        // ---------- ④ 组装 ----------
        List<BillVO> vos = new ArrayList<>(bills.size());
        for (Bill b : bills) {
            Long accountId = accountIdByBillId.get(b.getId());
            vos.add(new BillVO(
                    b.getId(),
                    b.getBookId(),
                    bookNames.get(b.getBookId()),
                    b.getCategoryId(),
                    categoryNames.get(b.getCategoryId()),
                    b.getType(),
                    b.getAmount(),
                    b.getRemark(),
                    b.getBillTime(),
                    b.getSource(),
                    // 未选账户时这里是 null —— 正常情况，前端需处理
                    accountId,
                    accountId == null ? null : accountNames.get(accountId),
                    b.getCreateTime()
            ));
        }
        return vos;
    }
}
