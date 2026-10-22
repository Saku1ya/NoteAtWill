package com.sakuya.notesatwill.entity.VO;

import java.math.BigDecimal;

/**
 * 预算的<b>执行状态</b>（实时聚合）。
 *
 * <p><b>★★ 最关键的约束：{@code remaining} 与 {@code usedPercent} 绝不落库</b>。
 * 它们完全由 {@code amount}（配置）与 {@code used}（实时聚合）推导：
 * <pre>
 *   used       = SUM(本月该范围内的支出)      ← 每次都现查
 *   remaining  = amount - used                ← 推导，不存
 *   usedPercent= used / amount * 100          ← 推导，不存
 * </pre>
 * 一旦把剩余额度写进数据库，就必须在"新增/修改/删除账单、新增/修改/删除预算"的
 * <b>每一个</b>路径上同步更新它 —— 漏一处就会出现"预算还剩 800，实际已花超"，
 * 而且对账也查不出来（因为预算表没有"正确值"的参照）。
 * <b>能算出来的东西不要存。</b>
 *
 * @param budgetId       预算 id
 * @param bookId         账本 id
 * @param bookName       账本名
 * @param categoryId     分类 id；<b>null 表示账本级预算</b>
 * @param categoryName   分类名；账本级时为 null
 * @param amount         每月额度
 * @param used           本月已用（实时聚合；分类级预算<b>含子分类</b>）
 * @param remaining      剩余 = {@code amount - used}（<b>可能为负</b>，表示已超支）
 * @param usedPercent    已用百分比（保留两位小数；额度为 0 时不会出现，因为额度必须 &gt; 0）
 * @param status         预警状态：NORMAL / WARNING / EXCEEDED
 * @param alertThreshold 预警阈值百分比（来自预算配置）
 * @param month          统计月份，格式 {@code yyyy-MM}
 * @author sakuya
 */
public record BudgetStatusVO(
        Long budgetId,
        Long bookId,
        String bookName,
        Long categoryId,
        String categoryName,
        BigDecimal amount,
        BigDecimal used,
        BigDecimal remaining,
        BigDecimal usedPercent,
        String status,
        Integer alertThreshold,
        String month) {
}
