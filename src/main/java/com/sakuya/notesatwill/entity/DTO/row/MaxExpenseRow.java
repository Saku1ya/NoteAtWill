package com.sakuya.notesatwill.entity.DTO.row;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 「最大单笔支出」的查询结果行。
 *
 * <p><b>★ 为什么需要单独一个行对象，而不是直接返回 {@code BigDecimal}</b>：
 * 「最大单笔」除了金额，还要带上<b>它是哪一天、什么分类</b> ——
 * 前端要显示"最大单笔：1,280 元（10-05 · 购物/数码）"。
 * 只返回金额的话，前端还要再查一次，那就白查了。
 *
 * <p><b>★ 用 POJO 而不是 record</b>：与 {@link CategorySumRow} 同理 ——
 * MyBatis 映射 record 依赖 {@code arg-name-based-constructor-auto-mapping}
 * （Spring Boot 默认<b>不</b>开），POJO + setter 按列名映射，行为确定。
 *
 * @author sakuya
 */
@Data
public class MaxExpenseRow {

    /** 该笔金额 */
    private BigDecimal amount;

    /** 该笔的记账时间 */
    private LocalDateTime billTime;

    /** 该笔的分类 id（前端可用来显示分类名） */
    private Long categoryId;

    /** 该笔的备注 */
    private String remark;
}
