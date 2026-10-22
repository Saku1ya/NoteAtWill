package com.sakuya.notesatwill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 解析记录表 t_ai_parse_log。
 *
 * <p>价值：记录每一次 AI 解析的原始输入、模型返回、token 消耗、用户是否采纳、
 * 用户修正了什么。这既是排查问题的依据，也是后续优化提示词的语料来源。
 * 面试时可量化的细节大多来自这张表。
 *
 * <p><b>本表没有逻辑删除字段</b>：解析日志是审计类数据，只增不删。
 *
 * <p>注意两个 JSON 字段（{@code resultJson} / {@code correctedJson}）在实体里
 * 先用 {@code String} 承载，避免引入额外的 JSON 类型处理器依赖；
 * 如果后续要按 JSON 内部字段查询，再换成相应类型并配 TypeHandler。
 *
 * @author sakuya
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_ai_parse_log")
public class AiParseLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属用户 */
    private Long userId;

    /** 用户原始输入文本 */
    private String rawText;

    /** 实际调用的大模型名 */
    private String modelName;

    /** 输入 token 数（成本统计用） */
    private Integer promptTokens;

    /** 输出 token 数（成本统计用） */
    private Integer completionTokens;

    /** 大模型返回的原始结构 */
    private String resultJson;

    /** 解析出的笔数 */
    private Integer parsedCount;

    /** 状态：PENDING 待确认 / CONFIRMED 已采纳 / REJECTED 已放弃 */
    private String status;

    /** 用户修正后的结果，用于优化提示词 */
    private String correctedJson;

    /** 本次调用耗时（毫秒） */
    private Integer costMs;

    /** 创建时间 */
    private LocalDateTime createTime;
}
