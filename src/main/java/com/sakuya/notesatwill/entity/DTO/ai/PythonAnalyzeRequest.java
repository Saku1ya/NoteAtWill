package com.sakuya.notesatwill.entity.DTO.ai;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 发往 Python {@code POST /ai/analyze} 的请求：<b>Java 算好的统计数字</b>。
 *
 * <p><b>★★ 本 DTO 的设计意图：让模型"没有机会算错"</b>
 * <pre>
 *   ❌ 让模型自己算"餐饮占多少"  → 必然出错，且每次错得不一样
 *   ✅ Java 算好直接喂给它        → 数字必然与图表一致（验收要求）
 * </pre>
 * 所以这里的每个数字都来自 {@code StatsService} 的既有聚合
 * （与 {@code /stats/summary}、{@code /stats/category-ratio} 同源），
 * <b>点评里提到的金额与页面上的图表一定是同一个数</b>。
 *
 * <p><b>★ 时间字段要显式 ISO 格式</b>：与 {@code PythonParseRequest} 同理 ——
 * 项目全局 Jackson 用空格分隔，Python 用 ISO（带 T），必须单字段覆盖。
 *
 * @param now                 当前时间（Java 注入）
 * @param month               统计月份 {@code yyyy-MM}
 * @param income              总收入
 * @param expense             总支出
 * @param surplus             结余
 * @param dailyAverage        日均支出（分母是已过天数）
 * @param days                日均的分母天数
 * @param topCategories       支出占比 Top N（已由 Java 排序）
 * @param maxExpense          最大单笔支出；无支出时为 null
 * @param maxExpenseCategory  最大单笔的分类名；与 {@code maxExpense} 同生共死
 * @param personaName         当前生效角色的名字（可空）
 * @param personaPersonality  角色设定（可空）
 * @param personaRelation     与用户的关系（可空）
 * @param personaAddress      对用户的称呼（可空）
 * @param personaGender       角色性别 MALE/FEMALE/OTHER（可空）
 * @author sakuya
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PythonAnalyzeRequest(
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime now,

        String month,

        BigDecimal income,
        BigDecimal expense,
        BigDecimal surplus,
        BigDecimal dailyAverage,
        long days,

        List<CategoryStat> topCategories,

        BigDecimal maxExpense,
        String maxExpenseCategory,

        String personaName,
        String personaPersonality,
        String personaRelation,
        String personaAddress,
        String personaGender) {

    /**
     * 一个分类的占比数据。
     *
     * @param name    分类名（一级分类）
     * @param amount  金额
     * @param percent 占总支出百分比
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CategoryStat(String name, BigDecimal amount, BigDecimal percent) {
    }
}
