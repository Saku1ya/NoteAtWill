package com.sakuya.notesatwill.entity.VO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预算的<b>配置信息</b>（不含执行情况）。
 *
 * <p>用于 CRUD 的列表与详情。要查"这个月用了多少"请用
 * {@link BudgetStatusVO} —— 那是实时聚合出来的，不属于预算配置本身。
 *
 * <p><b>★ 为什么不把"已用/剩余"也放进这个 VO</b>：
 * 预算列表通常有 N 条，每条都要一次聚合查询的话，接口会随预算数量线性变慢
 * （N 条预算 = N 次聚合 SQL）。所以配置与执行状态分成两个接口：
 * 列表只查配置，需要执行情况时再单独查（或前端按需查某一条）。
 *
 * @param id             预算 id
 * @param bookId         账本 id
 * @param bookName       账本名（前端展示用，避免二次查询）
 * @param categoryId     分类 id；<b>null 表示账本级预算</b>
 * @param categoryName   分类名；账本级时为 null
 * @param amount         每月额度
 * @param alertThreshold 预警阈值百分比
 * @param createTime     创建时间
 * @param updateTime     更新时间
 * @author sakuya
 */
public record BudgetVO(
        Long id,
        Long bookId,
        String bookName,
        Long categoryId,
        String categoryName,
        BigDecimal amount,
        Integer alertThreshold,
        LocalDateTime createTime,
        LocalDateTime updateTime) {
}
