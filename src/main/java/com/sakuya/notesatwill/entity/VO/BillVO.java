package com.sakuya.notesatwill.entity.VO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 账单列表项 / 详情。
 *
 * <p><b>★ 为什么必须用 VO 而不是直接返回 {@code Bill} 实体</b>：
 * {@code t_bill} 里只存了 {@code bookId} / {@code categoryId}，
 * <b>没有任何账户字段</b>（这是"记账与账户是两个独立功能"的刻意设计）。
 * 而列表上要显示"这笔账用了哪个账户"，这个信息必须跨表补查。
 * 实体里根本没有这个字段，所以必须由 VO 承载。
 *
 * <p>同时 VO 里带上 {@code categoryName} / {@code bookName} / {@code accountName}：
 * 前端拿到 {@code categoryId=1001} 没有任何意义，它要显示"餐饮/午餐"。
 * 若让前端自己去查名字，每个页面都要写一遍映射逻辑，且会引入 N+1 请求。
 *
 * <p><b>★ 哪些字段可能为 null</b>：
 * <table border="1">
 *   <caption>可空字段及其原因</caption>
 *   <tr><th>字段</th><th>何时为 null</th></tr>
 *   <tr>
 *     <td>{@code bookName} / {@code categoryName}</td>
 *     <td>{@code t_bill} 里这两个是 NOT NULL、且 Service 层保证归属，
 *         正常不会为 null。<b>唯一例外</b>：分类被逻辑删除后，
 *         {@code @TableLogic} 会自动把它从查询结果里过滤掉，
 *         于是批量补查时查不到名字 —— 此时为 null，
 *         前端应显示"已删除分类"而不是崩溃</td>
 *   </tr>
 *   <tr>
 *     <td>{@code accountId} / {@code accountName}</td>
 *     <td><b>正常且经常为 null</b> —— 用户记账时账户是选填的。
 *         不选账户就只是一条消费记录，不产生任何资金流水。
 *         前端必须能处理"没有账户"的展示</td>
 *   </tr>
 * </table>
 *
 * <p><b>为什么不含 {@code deleted} 与 {@code userId}</b>：
 * {@code deleted} 是内部状态（查询已自动过滤，返回给前端恒为"未删除"）；
 * {@code userId} 是当前登录用户自己，前端不需要、也不该依赖它做判断。
 *
 * @param id           账单 id
 * @param bookId       账本 id
 * @param bookName     账本名（补查）
 * @param categoryId   分类 id
 * @param categoryName 分类名（补查）；分类已被删除时为 null
 * @param type         收支类型：EXPENSE / INCOME
 * @param amount       金额，恒为正数
 * @param remark       备注
 * @param billTime     记账时间（业务时间，可补记）
 * @param source       来源：MANUAL / AI
 * @param accountId    资金账户 id（补查）；<b>未选账户时为 null</b>
 * @param accountName  资金账户名（补查）；<b>未选账户时为 null</b>
 * @param createTime   入库时间
 * @author sakuya
 */
public record BillVO(
        Long id,
        Long bookId,
        String bookName,
        Long categoryId,
        String categoryName,
        String type,
        BigDecimal amount,
        String remark,
        LocalDateTime billTime,
        String source,
        Long accountId,
        String accountName,
        LocalDateTime createTime) {
}
