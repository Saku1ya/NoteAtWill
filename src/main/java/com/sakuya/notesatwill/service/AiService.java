package com.sakuya.notesatwill.service;

import com.sakuya.notesatwill.client.AiParseClient;
import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Account;
import com.sakuya.notesatwill.entity.AiParseLog;
import com.sakuya.notesatwill.entity.Bill;
import com.sakuya.notesatwill.entity.DTO.AiBillItem;
import com.sakuya.notesatwill.entity.DTO.AiConfirmRequest;
import com.sakuya.notesatwill.entity.DTO.AiParseRequest;
import com.sakuya.notesatwill.entity.DTO.ai.PythonParseRequest;
import com.sakuya.notesatwill.entity.DTO.ai.PythonParseResponse;
import com.sakuya.notesatwill.entity.VO.AiParseVO;
import com.sakuya.notesatwill.entity.VO.CategoryTreeVO;
import com.sakuya.notesatwill.mapper.AiParseLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AI 账单解析服务（Java 侧编排）。
 *
 * <p><b>★ 本类的职责边界</b>：它是"调用前组装、调用后校验、然后存日志"的编排者，
 * <b>不负责落库</b> —— 真正写 {@code t_bill} 的是 {@link BillService#confirmAiBills}。
 *
 * <p><b>★★ 为什么 AI 解析【禁止直接落库】</b>（架构铁律）：
 * 整条链路必须是「解析 → 前端预览 → 用户确认 → 批量落库」。
 * 模型会算错金额、编造分类、匹配错账户；如果解析完就写库，
 * 用户没有任何机会发现，而账单错了是要影响余额和对账的。
 * 所以本类只产出一份 <b>PENDING 状态的草稿</b>，落库必须由用户确认触发。
 *
 * <p><b>★ 为什么 {@link #parse} 不加 {@code @Transactional}</b>：
 * 它要发起一次最长 15 秒的 HTTP 调用。若包在事务里，
 * 数据库连接会被白白占用 15 秒 —— 并发几个请求就能把连接池耗尽。
 * 本方法只有一条 {@code INSERT}（存日志），单语句本身就是原子的，不需要事务。
 * <b>调用外部服务时绝不要持有数据库事务。</b>
 *
 * @author sakuya
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    /** 解析日志状态：待用户确认 */
    public static final String STATUS_PENDING = "PENDING";
    /** 解析日志状态：用户已采纳 */
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    /** 解析日志状态：用户已放弃 */
    public static final String STATUS_REJECTED = "REJECTED";

    /** 与前端 DTO 的 {@code @Size} 保持一致；这里是第二道防线（内部调用也拦） */
    private static final int MAX_TEXT_LENGTH = 500;
    /** 与前端 DTO 的 {@code @Size} 一致；防止模型一次吐出几十笔把前端撑爆 */
    private static final int MAX_BILLS = 20;

    private final AiParseClient aiClient;
    private final AiParseLogMapper aiParseLogMapper;
    private final CategoryService categoryService;
    private final AccountService accountService;
    private final BillService billService;
    private final ObjectMapper objectMapper;

    // ========================================================================
    // 解析（任务 4.8）
    // ========================================================================

    /**
     * 解析一段自然语言，返回<b>待用户确认的草稿</b>，并留存一条 PENDING 日志。
     *
     * <p>流程：校验文本 → 组装候选 → 调 Python → 二次校验/归一化 → 存日志 → 返回。
     *
     * @param userId  当前登录用户 id
     * @param request 用户原文
     * @return 草稿（含 logId，确认时需带回）
     * @throws BusinessException 文本非法（1000）/ AI 服务不可用（5000）/ 超时（5001）/ 解析失败（5002）
     */
    public AiParseVO parse(Long userId, AiParseRequest request) {
        // ---------- ① 入参防御（不浪费一次外部调用）----------
        String text = request.text() == null ? "" : request.text().trim();
        if (text.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请输入要解析的内容");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "输入不能超过 " + MAX_TEXT_LENGTH + " 个字符");
        }

        // ---------- ② 组装候选（★ 必须按当前用户查，不能让前端传）----------
        // 若允许前端传候选，就能传入别人的分类 id 来探测别人的数据。
        List<PythonParseRequest.Category> categories = buildCategoryCandidates(userId);
        List<PythonParseRequest.Account> accounts = buildAccountCandidates(userId);
        LocalDateTime now = LocalDateTime.now();

        PythonParseRequest pyRequest = new PythonParseRequest(text, now, categories, accounts);

        // ---------- ③ 调 Python（限时 + 降级）----------
        long startMs = System.currentTimeMillis();
        PythonParseResponse pyResponse;
        try {
            pyResponse = aiClient.parseBill(pyRequest);
        } catch (RestClientException e) {
            // ★★ 只用一个 catch，【不在异常类上做区分】。
            //   实测结论（见 AiClientTimeoutTest）：读超时的真实形状是
            //     RestClientException("Error while extracting response ...")
            //       └─ SocketTimeoutException: Read timed out
            //   而【不是】ResourceAccessException。
            //   原因：请求其实已经发出去了，是"读响应"时超时，
            //   Spring 在把响应转成 DTO 的阶段失败，于是抛出"提取响应失败"，
            //   SocketTimeoutException 只能从 cause 链里找。
            //   若按异常类区分，超时会被永远错判成"服务不可用"。
            throw translateFailure(e);
        }
        int costMs = (int) (System.currentTimeMillis() - startMs);

        // ---------- ④ 二次校验与归一化（★ 不信任 Python 的返回）----------
        List<AiBillItem> items = normalizeAndValidate(userId, pyResponse, categories, now);

        // ---------- ⑤ 存日志（PENDING）----------
        AiParseLog logRow = new AiParseLog();
        logRow.setUserId(userId);
        logRow.setRawText(text);
        // modelName 留空：Python 的 /ai/parse-bill 响应里没有回传模型名。
        // 若将来要做成本统计，需要在 Python 侧加一个 model 字段。
        logRow.setModelName(null);
        logRow.setResultJson(toJson(pyResponse));
        logRow.setParsedCount(items.size());
        logRow.setStatus(STATUS_PENDING);
        logRow.setCostMs(costMs);
        aiParseLogMapper.insert(logRow);

        log.info("AI 解析完成：userId={}, logId={}, 笔数={}, 耗时={}ms",
                userId, logRow.getId(), items.size(), costMs);

        return new AiParseVO(logRow.getId(), items,
                pyResponse.unmatched() == null ? List.of() : pyResponse.unmatched());
    }

    // ========================================================================
    // 确认落库（任务 4.9）
    // ========================================================================

    /**
     * 用户核对后确认落库。
     *
     * <p><b>★★ 整个方法必须在一个事务里</b>，因为要保证：
     * <pre>
     *   N 笔账单写入  +  日志状态改成 CONFIRMED
     *        ↑ 要么全成功，要么全失败
     * </pre>
     * 否则会出现"日志说已确认、但账单只落了一半"这种用户完全无法理解的状态。
     * 具体的批量插入在 {@link BillService#confirmAiBills}（它也是
     * {@code @Transactional}，默认传播行为会加入本方法的事务）。
     *
     * <p><b>★ 日志校验的两条</b>：
     * <ol>
     *   <li>必须属于当前用户 —— 否则能拿别人的 logId 落库</li>
     *   <li>状态必须是 PENDING —— 否则同一次解析可以被重复确认，落两遍账</li>
     * </ol>
     *
     * @param userId  当前登录用户 id
     * @param request 确认请求（logId + bookId + 草稿列表）
     * @return 落库后的账单
     * @throws BusinessException 日志不存在/不属于我/已被确认（5005、5006）/ 任一笔字段非法
     */
    @Transactional
    public List<Bill> confirm(Long userId, AiConfirmRequest request) {
        // ---------- ① 校验解析日志 ----------
        AiParseLog logRow = aiParseLogMapper.selectById(request.logId());
        // "不存在"与"不属于我"返回同一个错误码，避免被用来枚举别人的 logId
        if (logRow == null || !Objects.equals(logRow.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.AI_LOG_NOT_FOUND);
        }
        if (!STATUS_PENDING.equals(logRow.getStatus())) {
            // 重复确认会造成重复记账，必须拦住
            throw new BusinessException(ErrorCode.AI_LOG_ALREADY_CONFIRMED);
        }

        // ---------- ② 批量落库（与日志更新同一事务）----------
        List<Bill> bills = billService.confirmAiBills(
                userId, logRow.getId(), request.bookId(), request.bills());

        // ---------- ③ 更新日志：状态 + 用户修正结果 ----------
        AiParseLog update = new AiParseLog();
        update.setId(logRow.getId());
        update.setStatus(STATUS_CONFIRMED);
        // 存"用户实际提交的草稿"：与 result_json 对比就能算出用户改了什么，
        // 这是将来优化 Prompt 的数据来源。
        // ★ 这里只存，不做差异计算 —— 差异是后面的分析工作，不该在写路径上做。
        update.setCorrectedJson(toJson(request.bills()));
        aiParseLogMapper.updateById(update);

        log.info("AI 确认落库：userId={}, logId={}, 笔数={}", userId, logRow.getId(), bills.size());
        return bills;
    }

    // ========================================================================
    // 内部：候选组装
    // ========================================================================

    /**
     * 把该用户可见的分类树压平成候选列表。
     *
     * <p><b>★ 为什么二级分类的名字要拼成「父/子」</b>：
     * 模型看到孤零零的「午餐」无法判断它属于餐饮还是别的父类；
     * 拼成「餐饮/午餐」后语义完整，匹配准确率明显更高。
     *
     * <p><b>★ 为什么要同时取 EXPENSE 和 INCOME 两棵树</b>：
     * 一段话里可能既有支出又有收入（"吃饭25，发工资8000"），
     * 只取一棵树会让另一半无法分类。
     *
     * @return 扁平候选（一级与二级都在里面，都可被选中）
     */
    private List<PythonParseRequest.Category> buildCategoryCandidates(Long userId) {
        List<PythonParseRequest.Category> result = new ArrayList<>();
        for (String type : new String[]{BillService.TYPE_EXPENSE, BillService.TYPE_INCOME}) {
            for (CategoryTreeVO level1 : categoryService.tree(userId, type)) {
                // 一级分类自身也可被选中（用户可以不细分到二级）
                result.add(new PythonParseRequest.Category(
                        level1.id(), level1.name(), level1.type()));
                // 二级分类用「父/子」命名
                if (level1.children() != null) {
                    for (CategoryTreeVO level2 : level1.children()) {
                        result.add(new PythonParseRequest.Category(
                                level2.id(), level1.name() + "/" + level2.name(), level2.type()));
                    }
                }
            }
        }
        return result;
    }

    /** 账户候选：只用于给模型提示名字，不要求它输出 id。 */
    private List<PythonParseRequest.Account> buildAccountCandidates(Long userId) {
        List<Account> accounts = accountService.list(userId);
        List<PythonParseRequest.Account> result = new ArrayList<>(accounts.size());
        for (Account account : accounts) {
            result.add(new PythonParseRequest.Account(account.getId(), account.getName()));
        }
        return result;
    }

    // ========================================================================
    // 内部：二次校验与归一化
    // ========================================================================

    /**
     * 校验 Python 返回的草稿，并归一化成前端可直接用的形状。
     *
     * <p><b>★★ 为什么 Python 已经校验过了，Java 还要再做一遍</b>：
     * Python 的校验只保证"它交出来的东西符合它自己的 schema"，
     * 但 Java <b>不能假设对方一定给对</b> ——
     * 网络中断可能拿到半截 JSON、Python 崩溃可能返回错误结构、
     * 模型升级可能改变行为。这是设计文档里「双向确认」的含义：
     * 两个独立服务各自保证自己的边界完整，<b>不是重复解析</b>。
     *
     * <p>做四件事：
     * <ol>
     *   <li><b>分类校验</b>：{@code categoryId} 必须在候选集内，且收支类型与账单一致，
     *       否则置 null（让前端提示用户手选）—— 这是防分类幻觉的最终防线</li>
     *   <li><b>回填分类名</b>：用候选集里的名字覆盖 Python 给的名字，
     *       保证 id 与 name 永远一致</li>
     *   <li><b>billTime 兜底</b>：null 则填 {@code now}（三层约定的第三层的前置，
     *       真正写库前 {@code confirmAiBills} 还会再兜一次）</li>
     *   <li><b>条数截断</b>：超过 {@code MAX_BILLS} 只保留前面部分</li>
     * </ol>
     *
     * @throws BusinessException Python 报失败，或一笔都没解析出来
     */
    private List<AiBillItem> normalizeAndValidate(Long userId,
                                                  PythonParseResponse pyResponse,
                                                  List<PythonParseRequest.Category> candidates,
                                                  LocalDateTime now) {
        if (pyResponse == null) {
            throw new BusinessException(ErrorCode.AI_RESULT_INVALID);
        }
        // Python 明确报失败（模型超时、格式重试后仍不合法、没识别出账单）
        if (!Boolean.TRUE.equals(pyResponse.success())) {
            String reason = pyResponse.error() == null ? "AI 解析失败" : pyResponse.error();
            log.warn("AI 解析未成功：userId={}, reason={}", userId, reason);
            // ★ 把 Python 的原始原因透传出去：前端才能给出有针对性的提示
            //   （"输入过长" / "没有识别出任何账单" / "未配置 Key"）
            throw new BusinessException(ErrorCode.AI_PARSE_FAILED, reason);
        }

        // 候选集索引：id → 候选（防幻觉的唯一依据）
        Map<Long, PythonParseRequest.Category> byId = new HashMap<>();
        for (PythonParseRequest.Category c : candidates) {
            byId.put(c.id(), c);
        }

        List<PythonParseResponse.Bill> rawBills =
                pyResponse.bills() == null ? List.of() : pyResponse.bills();
        if (rawBills.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_PARSE_EMPTY);
        }

        List<AiBillItem> result = new ArrayList<>(rawBills.size());
        for (PythonParseResponse.Bill raw : rawBills) {
            if (result.size() >= MAX_BILLS) {
                log.warn("解析出 {} 笔，截断到 {} 笔", rawBills.size(), MAX_BILLS);
                break;
            }

            // ---- ① 分类校验 ----
            Long categoryId = raw.categoryId();
            String categoryName = null;
            if (categoryId != null) {
                PythonParseRequest.Category cand = byId.get(categoryId);
                if (cand == null) {
                    // 模型编了候选集外的 id
                    log.warn("丢弃幻觉分类 categoryId={}（不在候选集内）", categoryId);
                    categoryId = null;
                } else if (!Objects.equals(cand.type(), raw.type())) {
                    // 分类存在但收支类型相反（例如支出配了收入分类）
                    log.warn("丢弃类型不匹配的分类 categoryId={}（分类 {} / 账单 {}）",
                            categoryId, cand.type(), raw.type());
                    categoryId = null;
                } else {
                    // 用候选集里的名字，不用 Python 给的 —— 保证 id 与 name 一致
                    categoryName = cand.name();
                }
            }

            // ---- ② billTime 兜底 ----
            LocalDateTime billTime = raw.billTime() != null ? raw.billTime() : now;

            // ---- ③ 组装 ----
            // accountId 此时一定是 null：账户由用户在前端确认时选择
            result.add(new AiBillItem(
                    raw.amount(), raw.type(), categoryId, categoryName,
                    null, raw.accountHint(), billTime, raw.remark(), raw.confidence()));
        }

        return result;
    }

    // ========================================================================
    // 内部：工具
    // ========================================================================

    /**
     * 把 HTTP 层的失败翻译成业务错误码。
     *
     * <p><b>★ 为什么按 cause 链判断，而不是按异常类</b>：
     * 实测（{@code AiClientTimeoutTest}）发现，读超时的真实异常形状是
     * <pre>
     *   RestClientException("Error while extracting response ...")
     *     └─ SocketTimeoutException: Read timed out
     * </pre>
     * <b>并没有 {@code ResourceAccessException}</b> —— 因为请求已经发出去了，
     * 是"读响应"阶段超时，Spring 在把响应转成 DTO 时失败，报的是"提取响应失败"。
     * 所以只能统一在 cause 链里找 {@link SocketTimeoutException}。
     *
     * <p><b>★ 为什么要区分"超时"和"不可用"</b>：
     * <ul>
     *   <li><b>超时</b>（读超时 / 连接超时）→ 模型太慢或网络抖动，
     *       用户重试可能成功 → 提示"响应超时，请稍后再试"</li>
     *   <li><b>不可用</b>（连接被拒 / 找不到主机 / 4xx-5xx）→ Python 没启动或出错，
     *       重试也没用 → 提示"AI 服务暂时不可用"</li>
     * </ul>
     * 混成一个码会让排查时无法区分该看进程还是看模型。
     *
     * @param e 客户端抛出的任意 HTTP 层异常（含子类）
     */
    private BusinessException translateFailure(RestClientException e) {
        // 从 e 本身开始找：若某天 Spring 直接把 SocketTimeoutException 抛出来也能覆盖
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                log.error("调用 AI 服务超时（cause 链中找到 SocketTimeoutException）：{}",
                        e.getMessage());
                return new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT);
            }
            cause = cause.getCause();
        }
        log.error("AI 服务不可用（非超时失败）：{}", e.getMessage());
        return new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
    }

    /**
     * 序列化成 JSON 字符串（写入 {@code result_json} / {@code corrected_json}）。
     *
     * <p>失败时返回 null 而不是抛异常 —— <b>不能因为"记日志失败"而让整个解析失败</b>。
     * 日志是辅助信息，主流程优先。
     */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("序列化失败，跳过该字段：{}", e.getMessage());
            return null;
        }
    }
}
