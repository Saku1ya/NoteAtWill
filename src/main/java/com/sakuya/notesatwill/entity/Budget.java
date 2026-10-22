package com.sakuya.notesatwill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 月度预算表 t_budget。
 *
 * <p><b>预算只做月度</b>，一个账本一个月一条。周期就是自然月，
 * 所以本表<b>没有 periodType 字段</b>——一个恒等于 MONTH 的字段不提供任何表达力，
 * 只会让读代码的人反复确认它到底有没有别的取值。
 * 将来真要做周预算，加回该字段并调整聚合口径即可，属于可逆的低成本演进。
 *
 * <p>两种粒度：
 * <ul>
 *   <li>{@code categoryId == null} → 账本级月度预算（整个账本这个月多少钱）</li>
 *   <li>{@code categoryId} 有值 → 该分类这个月的预算</li>
 * </ul>
 *
 * <p><b>剩余额度 = amount - 本月已消费合计，每次查询实时聚合，绝不落库扣减。</b>
 * 一旦用户改了历史账单，扣减值就对不上，还要写一整套回滚逻辑。
 *
 * <p>时间范围用 {@code bill_time} 落在 {@code [当月 1 日 00:00:00, 次月 1 日 00:00:00)} 界定。
 * 选月度还有一个直接收益：能一对一映射到日历图表，每天一格显示当天消费额，
 * 纵向累加成月合计，正好等于预算已用量。
 *
 * <p>唯一性约束 {@code (book_id, category_id)} 在 Service 层校验，不建唯一索引
 * ——{@code categoryId} 为 NULL 的账本级预算挡不住重复建。
 *
 * @author sakuya
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_budget")
public class Budget implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属用户 */
    private Long userId;

    /** 所属账本 */
    private Long bookId;

    /** 分类：NULL 表示账本级月度预算 */
    private Long categoryId;

    /** 每月额度 */
    private BigDecimal amount;

    /** 预警阈值百分比，默认 80 表示用到 80% 就提醒 */
    private Integer alertThreshold;

    /** 逻辑删除：0 正常，1 已删除 */
    @TableLogic
    private Integer deleted;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
