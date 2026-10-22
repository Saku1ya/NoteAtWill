package com.sakuya.notesatwill.entity.VO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 类型占比（饼图数据）：按<b>一级分类</b>聚合，可下钻到二级。
 *
 * <p><b>★ 为什么一级分类的金额要把子分类"上卷"上来</b>：
 * {@code t_bill.category_id} 存的是<b>叶子分类</b>（用户记账时选的是「餐饮/午餐」），
 * 直接分组会得到"餐饮/午餐 300、餐饮/晚餐 200"这种碎片，
 * 饼图上会出现十几个小扇区。所以要把二级的金额加到它的一级父类上。
 *
 * <p><b>★ 上卷在 Java 里做，不在 SQL 里做</b>：
 * SQL 需要 {@code CASE WHEN parent_id = 1001 THEN 1001 ...} 或自连接，
 * 分类一多就没法看；而分类树只有两级、单月最多几十个分类，
 * 在内存里用 {@code Map<id, parentId>} 映射更清晰，也便于同时产出下钻用的 children。
 *
 * <p><b>★ {@code percent} 保留两位小数，各项之和可能不等于 100.00</b>
 * （四舍五入的累积误差，最多差几个 0.01）。前端若要做"必须凑满 100%"的饼图，
 * 应该用 {@code amount} 自己算，或用最后一个扇区吃掉误差。
 *
 * @param categoryId   分类 id（一级）
 * @param categoryName 分类名（一级）
 * @param amount       该一级分类下所有支出的合计
 * @param percent      占总支出百分比（0~100，两位小数）
 * @param children     下钻后的二级明细；未下钻、或该一级分类下没有二级明细时为空列表
 * @author sakuya
 */
public record CategoryRatioVO(
        Long categoryId,
        String categoryName,
        BigDecimal amount,
        BigDecimal percent,
        List<CategoryRatioVO> children) {

    /** 二级明细没有更深的下钻，children 恒为空 —— 用这个工厂避免到处写 {@code List.of()} */
    public static CategoryRatioVO leaf(Long categoryId, String categoryName,
                                       BigDecimal amount, BigDecimal percent) {
        return new CategoryRatioVO(categoryId, categoryName, amount, percent, List.of());
    }
}
