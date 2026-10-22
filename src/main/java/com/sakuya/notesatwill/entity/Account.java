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
 * 资金账户表 t_account（全局共享，不按账本隔离）。
 *
 * <p><b>余额为什么存两份</b>：
 * <ul>
 *   <li>{@code initBalance} 期初余额 —— 用户建账户时填写的初始金额，此后永不改变</li>
 *   <li>{@code balance} 当前余额 —— 冗余字段，避免每次查账户列表都做聚合</li>
 * </ul>
 *
 * <p><b>⚠ 核心纪律：{@code balance} 只能由「资金流水服务」修改。</b>
 * 它在写 {@code t_account_flow} 时同步维护余额，禁止在其它 Service 里直接 update。
 * 这条纪律是账户数据不出错的前提。
 *
 * <p>对账公式（因为资金流水已独立成表，只需查一张表）：
 * <pre>
 *   balance == initBalance + SUM(方向 IN 的金额) - SUM(方向 OUT 的金额)
 * </pre>
 *
 * @author sakuya
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_account")
public class Account implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属用户 */
    private Long userId;

    /** 账户名：现金 / 微信零钱 / 支付宝 / 招商银行卡… */
    private String name;

    /** 账户类型：CASH / WECHAT / ALIPAY / BANK / OTHER */
    private String type;

    /** 期初余额，建账户时填写，之后不变 */
    private BigDecimal initBalance;

    /** 当前余额（冗余字段，只能由余额服务修改） */
    private BigDecimal balance;

    /** 图标标识 */
    private String icon;

    /** 排序值 */
    private Integer sort;

    /** 是否默认账户：1 是 */
    private Integer isDefault;

    /** 逻辑删除：0 正常，1 已删除 */
    @TableLogic
    private Integer deleted;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
