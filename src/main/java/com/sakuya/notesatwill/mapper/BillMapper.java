package com.sakuya.notesatwill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sakuya.notesatwill.entity.Bill;
import com.sakuya.notesatwill.entity.DTO.row.CategorySumRow;
import com.sakuya.notesatwill.entity.DTO.row.DaySumRow;
import com.sakuya.notesatwill.entity.DTO.row.MaxExpenseRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 账单 Mapper。
 *
 * <p><b>为什么这里有一个手写 SQL 的聚合方法</b>：
 * {@code SELECT SUM(amount) ... GROUP BY 无} 这类"整表聚合成一行"的查询，
 * 用 MyBatis-Plus 的 {@code QueryWrapper.select(...)} 也能写，但字段名是字符串、
 * 且要自己确认 {@code @TableLogic} 是否被追加 —— 对一个<b>统计口径</b>来说，
 * 那点不确定性不值得留。手写 SQL 把条件写全，一眼可核对。
 *
 * <p><b>★ 代价：手写 SQL 必须自己写 {@code deleted = 0}</b> ——
 * {@code @TableLogic} 只对 MyBatis-Plus 自动生成的 SQL 生效。
 *
 * @author sakuya
 */
@Mapper
public interface BillMapper extends BaseMapper<Bill> {

    /**
     * 统计某账本在给定时间区间内、某收支类型的金额合计。
     *
     * <p><b>这是全项目唯一的「月度金额聚合」口径入口</b>，
     * 供账单列表页汇总（{@code /api/bills/summary}）、
     * 统计模块（{@code /api/stats/*}）与每日问候卡片共用。
     * <b>禁止在别处再写一份同样的 SQL</b> —— 否则同一个页面会出现两个不同的数字。
     *
     * <p><b>★ {@code COALESCE(SUM(amount), 0)} 不能省</b>：
     * 没有任何匹配行时 {@code SUM} 返回的是 <b>NULL 而不是 0</b>，
     * 不包 COALESCE 会让调用方拿到 null 并在后续运算里 NPE。
     *
     * <p><b>时间区间为左闭右开</b>（{@code >= start} 且 {@code < end}）——
     * 这样"整个 10 月"传 {@code [10-01 00:00, 11-01 00:00)} 即可，
     * 不需要猜月末最后一刻是 23:59:59 还是 23:59:59.999。
     *
     * @param userId 归属用户（数据隔离的硬条件）
     * @param bookId 账本
     * @param type   收支类型：EXPENSE / INCOME
     * @param start  区间起点（含）
     * @param end    区间终点（<b>不含</b>）
     * @return 金额合计，<b>无数据时返回 0</b>（不会返回 null）
     */
    @Select("""
            SELECT COALESCE(SUM(amount), 0)
              FROM t_bill
             WHERE user_id = #{userId}
               AND book_id = #{bookId}
               AND type = #{type}
               AND deleted = 0
               AND bill_time >= #{start}
               AND bill_time < #{end}
            """)
    BigDecimal sumAmount(@Param("userId") Long userId,
                         @Param("bookId") Long bookId,
                         @Param("type") String type,
                         @Param("start") LocalDateTime start,
                         @Param("end") LocalDateTime end);

    /**
     * 按<b>分类</b>聚合某区间内的金额。
     *
     * <p><b>★ 这一个方法同时服务两个功能，避免口径分叉</b>：
     * <ul>
     *   <li><b>分类级预算的已用量</b>（{@code /api/budgets/status}）——
     *       调用方按"该分类 + 其子分类"的 id 集合在内存里求和</li>
     *   <li><b>类型占比</b>（{@code /api/stats/category-ratio}）——
     *       调用方把二级分类的金额上卷到一级分类</li>
     * </ul>
     * 为什么不在 SQL 里直接按"一级分类"聚合？因为 {@code t_bill} 只存叶子分类 id，
     * 上卷需要 {@code t_category.parent_id}。在 Java 里做映射比写
     * {@code CASE WHEN} / 自连接清晰得多，而且分类树只有两级、数据量极小。
     *
     * <p><b>★ 为什么不返回没有账单的分类</b>：本方法只返回有数据的行。
     * "某分类本月花 0 元"是否需要出现在结果里，取决于调用方（占比图通常不需要）。
     *
     * <p><b>★ 时间区间为左闭右开</b>（{@code >= start} 且 {@code < end}），
     * 与 {@link #sumAmount} 完全一致 —— 两处口径必须相同，否则同一页面出现两个数。
     *
     * @param userId 归属用户
     * @param bookId 账本
     * @param type   收支类型
     * @param start  区间起点（含）
     * @param end    区间终点（<b>不含</b>）
     * @return 每个分类一行的金额合计；<b>只含有数据的分类</b>
     */
    @Select("""
            SELECT category_id AS categoryId,
                   COALESCE(SUM(amount), 0) AS total
              FROM t_bill
             WHERE user_id = #{userId}
               AND book_id = #{bookId}
               AND type = #{type}
               AND deleted = 0
               AND bill_time >= #{start}
               AND bill_time < #{end}
             GROUP BY category_id
            """)
    List<CategorySumRow> sumGroupByCategory(@Param("userId") Long userId,
                                            @Param("bookId") Long bookId,
                                            @Param("type") String type,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

    /**
     * 按<b>天</b>聚合某区间内的金额。
     *
     * <p><b>★ 只返回有数据的日期</b>（{@code GROUP BY DATE(bill_time)} 的特性）。
     * <b>补 0 是 Service 层的责任</b>，不是 SQL 的：
     * <ul>
     *   <li>日历要 28~31 天连续 —— 补到几号取决于月份（闰年 2 月 29 天），
     *       只有 {@code YearMonth.atEndOfMonth()} 能可靠给出</li>
     *   <li>趋势要时间轴连续 —— 补的天数取决于用户选的区间</li>
     * </ul>
     * 若在 SQL 里用递归 CTE 硬凑连续日期，写法复杂且日期函数因数据库而异。
     *
     * <p><b>★ 为什么要按"天"而不是按"周/月"直接分组</b>：
     * 一次按天取出，日历、日趋势、周趋势、月趋势都由同一份数据在内存里聚合 ——
     * <b>四种粒度的口径天然一致</b>。一个月的天数最多 31 行，内存开销可忽略。
     *
     * <p><b>★ {@code categoryIds} 的作用</b>：分类级预算的日历只统计
     * "该分类 + 其子分类"的支出，所以需要按分类集合过滤。
     * <b>传 {@code null} 或空集合表示不过滤</b>（账本级预算、以及趋势图都用这种）。
     *
     * @param userId      归属用户
     * @param bookId      账本
     * @param type        收支类型
     * @param start       区间起点（含）
     * @param end         区间终点（<b>不含</b>）
     * @param categoryIds 可选的分类 id 集合；{@code null}/空 = 不过滤
     * @return 每个有支出的日期一行
     */
    @Select("""
            <script>
            SELECT DATE(bill_time) AS day,
                   COALESCE(SUM(amount), 0) AS total
              FROM t_bill
             WHERE user_id = #{userId}
               AND book_id = #{bookId}
               AND type = #{type}
               AND deleted = 0
               AND bill_time &gt;= #{start}
               AND bill_time &lt; #{end}
               <if test="categoryIds != null and !categoryIds.isEmpty()">
                 AND category_id IN
                 <foreach collection="categoryIds" item="cid" open="(" separator="," close=")">
                   #{cid}
                 </foreach>
               </if>
             GROUP BY DATE(bill_time)
             ORDER BY day
            </script>
            """)
    List<DaySumRow> sumGroupByDay(@Param("userId") Long userId,
                                  @Param("bookId") Long bookId,
                                  @Param("type") String type,
                                  @Param("start") LocalDateTime start,
                                  @Param("end") LocalDateTime end,
                                  @Param("categoryIds") Collection<Long> categoryIds);

    /**
     * 查区间内<b>金额最大的一笔</b>支出。
     *
     * <p><b>★ 用 {@code ORDER BY ... LIMIT 1} 而不是 {@code MAX(amount)}</b>：
     * {@code MAX} 只能给出金额，拿不到"是哪一天、什么分类、什么备注" ——
     * 而前端要显示"最大单笔：1,280 元（10-05 · 购物/数码）"。
     * 用 LIMIT 1 一次查询拿到全部所需字段，避免"先 MAX 再按金额回查"的两步麻烦
     * （后者在有多笔同额时还可能查出多条）。
     *
     * <p><b>★ 排序要加 {@code id DESC} 兜底</b>：
     * 同额的两笔，SQL 不保证稳定顺序。加个次键让结果<b>可复现</b>，
     * 否则同样的请求两次返回不同的"最大单笔"，测试也会飘。
     *
     * <p><b>★ 无数据时返回 {@code null}</b>（不是空对象）：
     * 调用方据此把 VO 里的 {@code maxExpense} 置 null，前端显示"—"。
     *
     * @param userId 归属用户
     * @param bookId 账本
     * @param type   收支类型
     * @param start  区间起点（含）
     * @param end    区间终点（<b>不含</b>）
     * @return 最大的一笔；无数据时为 {@code null}
     */
    @Select("""
            SELECT amount, bill_time AS billTime, category_id AS categoryId, remark
              FROM t_bill
             WHERE user_id = #{userId}
               AND book_id = #{bookId}
               AND type = #{type}
               AND deleted = 0
               AND bill_time >= #{start}
               AND bill_time < #{end}
             ORDER BY amount DESC, id DESC
             LIMIT 1
            """)
    MaxExpenseRow findMaxAmount(@Param("userId") Long userId,
                                @Param("bookId") Long bookId,
                                @Param("type") String type,
                                @Param("start") LocalDateTime start,
                                @Param("end") LocalDateTime end);
}
