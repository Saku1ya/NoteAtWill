package com.sakuya.notesatwill.entity.DTO.ai;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 发往 Python {@code POST /ai/greeting} 的请求。
 *
 * <p><b>★★ 时段由 Java 算好传过去（{@code timeBucket}），不交给模型判断</b>：
 * 模型拿不到可靠时钟，也不知道用户时区，让它猜"现在是早上还是晚上"必然出错。
 *
 * <p><b>★ 数字同样由 Java 聚合</b>：与点评同理，模型只负责把数字说成一句人话，
 * 这样问候里的金额与首页统计必然一致。
 *
 * @param now                当前时间（ISO 格式，与 Python 契约统一）
 * @param timeBucket         时段：DAWN/MORNING/FORENOON/NOON/AFTERNOON/EVENING/NIGHT
 * @param weekday            星期几的中文写法
 * @param month              统计月份 {@code yyyy-MM}
 * @param expense            本月支出
 * @param dailyAverage       日均支出
 * @param days               日均的分母天数
 * @param todayExpense       今天的支出
 * @param hasBillToday       今天是否已记账（让问候更贴合实际）
 * @param personaName        生效角色名（可空）
 * @param personaPersonality 角色设定（可空）
 * @param personaRelation    与用户的关系（可空）
 * @param personaAddress     对用户的称呼（可空）
 * @param personaGender      角色性别 MALE/FEMALE/OTHER（可空）
 * @author sakuya
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PythonGreetingRequest(
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime now,

        String timeBucket,
        String weekday,

        String month,
        BigDecimal expense,
        BigDecimal dailyAverage,
        long days,

        BigDecimal todayExpense,
        boolean hasBillToday,

        String personaName,
        String personaPersonality,
        String personaRelation,
        String personaAddress,
        String personaGender) {
}
