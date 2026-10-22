package com.sakuya.notesatwill.entity.DTO.ai;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Python 服务 {@code POST /ai/parse-bill} 的响应体。
 *
 * <p><b>★ 这是"不可信数据"，绝不能直接落库。</b>
 * Python 侧虽然已经做过 Pydantic 校验与防幻觉，但 Java 不能假设"对方一定给对"：
 * 网络中断可能拿到半截 JSON、Python 崩溃可能返回错误结构、模型升级可能改变字段。
 * 所以 {@code AiService} 收到后<b>还要再做一轮校验与归一化</b>。
 *
 * <p>这就是设计文档里「双向确认」的含义 —— 不是重复解析，
 * 而是两个独立服务各自的边界完整性。
 *
 * <p><b>{@code ignoreUnknown = true}</b>：模型或 Python 侧偶尔会多吐字段，
 * 不能因为多一个字段就整批失败（与 Python 侧 {@code extra="ignore"} 对齐）。
 *
 * @param success   是否解析成功
 * @param bills     解析出的账单草稿
 * @param unmatched 无法归类的原始片段（避免静默丢弃）
 * @param error     {@code success=false} 时的原因
 * @author sakuya
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PythonParseResponse(
        Boolean success,
        List<Bill> bills,
        List<String> unmatched,
        String error) {

    /**
     * 单条解析结果（Python 侧的 {@code AiBillItem}）。
     *
     * <p><b>★ {@code billTime} 可空</b>：模型可能解析不出时间。
     * Python 侧已做兜底（填当前时刻），Java 侧再做一次（设计 Y 的三层约定），
     * 因为 {@code t_bill.bill_time} 是 NOT NULL，null 会直接报 SQL 错误。
     *
     * <p><b>★ {@code categoryId} 可空且不可信</b>：可能不在候选集内（幻觉），
     * 也可能是 null（模型找不到合适分类）。Java 侧必须按候选集重新校验。
     *
     * @param amount       金额，恒为正数（Python 侧已保证 &gt; 0）
     * @param type         EXPENSE / INCOME
     * @param categoryId   分类 id，可能为 null 或不在候选集内
     * @param categoryName 分类名，Python 已从候选集回填；不可信，Java 会重新查
     * @param accountHint  账户名提示（如"微信"），<b>不是 accountId</b>
     * @param billTime     记账时间，可空
     * @param remark       备注
     * @param confidence   0~1 的置信度
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bill(
            BigDecimal amount,
            String type,
            Long categoryId,
            String categoryName,
            String accountHint,

            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
            LocalDateTime billTime,

            String remark,
            BigDecimal confidence) {
    }
}
