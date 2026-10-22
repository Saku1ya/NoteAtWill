package com.sakuya.notesatwill.entity.DTO.row;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 「按分类聚合」的查询结果行。
 *
 * <p><b>★ 为什么用 POJO 而不是 record</b>：
 * MyBatis 把结果集映射到 record 需要<b>按参数名匹配构造器</b>，
 * 依赖 {@code arg-name-based-constructor-auto-mapping} 开启（Spring Boot 默认<b>不</b>开）。
 * 用 POJO + setter 是按列名映射，行为确定、无需额外配置。
 * <b>内部聚合行不值得为省几行代码去踩框架映射的不确定性。</b>
 *
 * @author sakuya
 */
@Data
public class CategorySumRow {

    /** 分类 id（{@code t_bill.category_id} 非空，所以这里一定有值） */
    private Long categoryId;

    /** 该分类的金额合计 */
    private BigDecimal total;
}
