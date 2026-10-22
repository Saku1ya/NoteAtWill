package com.sakuya.notesatwill.entity.DTO;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 账单分页查询条件。
 *
 * <p><b>所有筛选字段都是可选的</b>（null 表示"不按这个条件筛"），
 * 服务端按需拼接 SQL 条件 —— 这正是 MyBatis-Plus 那些
 * "带 boolean 条件重载"（如 {@code eq(boolean, column, value)}）的用武之地。
 *
 * <p><b>为什么用 record 承接查询参数</b>：Spring MVC 对 record 支持构造器绑定
 * （Spring 6+），9 个 {@code @RequestParam} 展开写会非常啰嗦且容易漏。
 * 前提是编译时带了 {@code -parameters}（Spring Boot 父 POM 默认开启）。
 *
 * @param bookId    按账本筛
 * @param type      按收支类型筛：EXPENSE / INCOME
 * @param categoryId 按分类筛
 * @param accountId <b>按资金账户筛 —— 这个条件要跨表</b>。
 *                  因为 {@code t_bill} 里没有 {@code account_id}（刻意设计），
 *                  必须通过 {@code t_account_flow} 关联查出用过该账户的账单 id。
 *                  <b>没选账户的账单因此天然不会被这个条件选中</b>，这正是期望行为
 * @param startTime 时间区间起点（含）。用 {@code >= startTime}
 * @param endTime   时间区间终点（<b>不含</b>）。用 {@code < endTime}
 * @param keyword   备注关键词，模糊匹配
 * @param page      页码，从 1 开始；缺省 1
 * @param size      每页条数；缺省 20，<b>服务端强制上限 100</b>
 * @author sakuya
 */
public record BillQuery(
        Long bookId,

        String type,

        Long categoryId,

        Long accountId,

        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime startTime,

        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime endTime,

        String keyword,

        Integer page,

        Integer size) {

    /** 每页条数上限：防止前端传 size=999999 把整表拉出来 */
    public static final int MAX_SIZE = 100;
    /** 每页条数缺省值 */
    public static final int DEFAULT_SIZE = 20;

    /**
     * 规范化页码：null 或小于 1 一律按第 1 页。
     *
     * <p>放在 DTO 里而不是 Service 里，是为了让"参数怎么算合法"这件事
     * 与字段定义待在一起，避免规则散落。
     */
    public int safePage() {
        return (page == null || page < 1) ? 1 : page;
    }

    /**
     * 规范化每页条数：缺省 20，且<b>强制上限 100</b>。
     *
     * <p>上限是<b>防御性措施</b>而非业务规则：没有它，
     * 一个 {@code size=999999} 的请求就能把整张表读进内存。
     */
    public int safeSize() {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
