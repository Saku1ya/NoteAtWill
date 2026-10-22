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
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 记账流水表 t_bill（核心表）。
 *
 * <p><b>只描述「我买了什么 / 赚了什么」</b>，不存任何账户字段。
 * 账户信息（如果用户选了的话）在 {@link AccountFlow} 里 —— 这是两张表拆分的根本原因。
 *
 * <p><b>记账与账户是两个独立功能</b>，由用户自主决定是否关联：
 * <ul>
 *   <li>不选账户 → 只写本表，纯消费记录，不影响任何账户余额</li>
 *   <li>选了账户 → 额外写一条 {@code t_account_flow}，扣减 / 增加该账户余额</li>
 * </ul>
 * 所以本表<b>没有 accountId 字段</b>，这是刻意设计，不是遗漏。
 *
 * <p><b>转账不在本表里</b>。转账是「账户之间搬钱」，属于账户模块的操作，
 * 不是消费行为。因此：
 * <ul>
 *   <li>{@code type} 只有 EXPENSE / INCOME 两种</li>
 *   <li>{@code categoryId} 恒为必填</li>
 *   <li>统计天然不含转账，不需要额外写过滤条件去排除</li>
 * </ul>
 *
 * @author sakuya
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_bill")
public class Bill implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属用户（冗余字段，便于权限校验与索引） */
    private Long userId;

    /** 所属账本 */
    private Long bookId;

    /** 分类，必填。转账不属于记账，不会出现在本表 */
    private Long categoryId;

    /** 类型：EXPENSE 支出 / INCOME 收入 */
    private String type;

    /** 金额，恒为正数，方向由 type 决定 */
    private BigDecimal amount;

    /** 备注 */
    private String remark;

    /** 记账时间（可与创建时间不同，支持补记） */
    private LocalDateTime billTime;

    /** 来源：MANUAL 手动 / AI 智能识别 / IMPORT 账单导入（二期预留） */
    private String source;

    /** 关联的 AI 解析记录，便于追溯 */
    private Long aiLogId;

    /** 逻辑删除：0 正常，1 已删除 */
    @TableLogic
    private Integer deleted;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
