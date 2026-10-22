package com.sakuya.notesatwill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日问候记录表 t_daily_greeting。
 *
 * <p><b>唯一索引 {@code uk_user_date (user_id, greet_date)} 是「每天只发一次」的唯一可靠保证。</b>
 * 刷新页面、开多个标签页、并发请求都会重复触发，
 * 应用层「先查后插」在并发下必然漏；唯一索引冲突则一定拦得住。
 *
 * <p><b>冲突时直接跳过，并且不调用大模型</b>——既防重复又省钱。
 *
 * <p><b>本表没有逻辑删除字段</b>：问候记录是幂等凭据，删掉就等于允许重发。
 *
 * @author sakuya
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_daily_greeting")
public class DailyGreeting implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属用户 */
    private Long userId;

    /** 问候日期 */
    private LocalDate greetDate;

    /** 时段：DAWN / MORNING / FORENOON / NOON / AFTERNOON / EVENING / NIGHT */
    private String timeBucket;

    /** 生成的问候语 */
    private String content;

    /** 生成用的模型名 */
    private String modelName;

    /** 创建时间 */
    private LocalDateTime createTime;
}
