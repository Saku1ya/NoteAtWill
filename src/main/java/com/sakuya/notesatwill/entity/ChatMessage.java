package com.sakuya.notesatwill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 对话消息表 t_chat_message。
 *
 * <p><b>关键设计：账单卡片只存 {@code billId}，不存金额快照。</b>
 * 因为账单与卡片要求双向级联删除，存快照必然导致两边数据打架；
 * 只存引用则卡片永远是账单的实时投影，账单改了卡片跟着变。
 *
 * <p>字段名用 {@code triggerType} 而不是 {@code trigger}
 * ——{@code TRIGGER} 是 MySQL 保留字，用它作字段名会踩坑。
 *
 * <p>时间分隔（截图里的 18:37、01:06）由前端按相邻消息时间差计算
 * （如超过 5 分钟插一条），不落库。
 *
 * @author sakuya
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_chat_message")
public class ChatMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属用户 */
    private Long userId;

    /** 统计卡片记录当时的账本口径 */
    private Long bookId;

    /** 发送方：USER / AI（预留 SYSTEM） */
    private String role;

    /** 消息类型：TEXT / BILL_CARD / STAT_CARD / GREETING */
    private String type;

    /** 文本内容 */
    private String content;

    /** BILL_CARD 时指向账单，不存金额快照 */
    private Long billId;

    /** 卡片附加信息（统计卡片的数字、账本名、统计时间等） */
    private String metaJson;

    /** 触发来源：MANUAL_BILL / AI_PARSE / DAILY_OPEN */
    private String triggerType;

    /** 逻辑删除：0 正常，1 已删除 */
    @TableLogic
    private Integer deleted;

    /** 消息时间，前端据此做时间分隔 */
    private LocalDateTime createTime;
}
