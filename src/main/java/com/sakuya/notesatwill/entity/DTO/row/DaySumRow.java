package com.sakuya.notesatwill.entity.DTO.row;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 「按天聚合」的查询结果行。
 *
 * <p><b>★ 只包含【有数据】的日期</b>：{@code GROUP BY DATE(bill_time)} 不会产出
 * 零支出的那些天。<b>补 0 是 Service 层的事</b>，不是 SQL 的事 ——
 * 日历要 28~31 天连续、趋势要时间轴连续，这些连续性只有拿到月份信息才能补。
 *
 * <p>若 SQL 里硬凑连续日期（如递归 CTE），不仅写法复杂，
 * 而且"要补到几号"取决于月份（闰年 2 月 29 天），交给 Java 的
 * {@code YearMonth.atEndOfMonth()} 更可靠。
 *
 * @author sakuya
 */
@Data
public class DaySumRow {

    /** 日期（{@code DATE(bill_time)}，不含时分秒） */
    private LocalDate day;

    /** 当天金额合计 */
    private BigDecimal total;
}
