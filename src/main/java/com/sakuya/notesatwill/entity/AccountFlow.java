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
 * 账户资金流水表 t_account_flow。
 *
 * <p><b>只描述「钱怎么动的」</b>，不带分类、不带账本。
 * {@code amount} 恒为正数，方向由 {@code direction} 决定：IN 转入，OUT 转出。
 *
 * <p><b>三种来源，由 flowType 区分</b>：
 * <table border="1">
 *   <caption>来源与流水条数</caption>
 *   <tr><th>来源</th><th>触发方式</th><th>流水条数</th></tr>
 *   <tr><td>BILL</td><td>用户记账时<b>选了</b>账户</td><td>1 条（支出 OUT / 收入 IN）</td></tr>
 *   <tr><td>TRANSFER</td><td>用户在账户模块做转账</td><td><b>2 条</b>，共享同一个 transferNo</td></tr>
 *   <tr><td>ADJUST</td><td>用户手动校正余额</td><td>1 条</td></tr>
 * </table>
 *
 * <p><b>为什么 billId 不能建唯一索引</b>：
 * <ol>
 *   <li>转账与手动调整都没有对应账单，{@code billId} 为 NULL。
 *       MySQL 唯一索引把多行 NULL 视为互不相等，起不到约束作用。</li>
 *   <li>表里有逻辑删除字段，唯一索引会在「删除后重建」时误报冲突。</li>
 * </ol>
 * 「一笔账单最多对应一条资金流水」这个约束由 Service 层保证。
 *
 * <p><b>撤销转账必须按 transferNo 成对删</b>，只删一条会让两个账户同时错。
 *
 * @author sakuya
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_account_flow")
public class AccountFlow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属用户（冗余字段，便于权限校验与索引） */
    private Long userId;

    /** 发生变动的账户 */
    private Long accountId;

    /** 来源：BILL 记账产生 / TRANSFER 转账 / ADJUST 手动调整 */
    private String flowType;

    /** 关联的记账记录，仅 BILL 类型有值 */
    private Long billId;

    /** 转账批次号，仅 TRANSFER 类型有值；一次转账的两条流水共享 */
    private String transferNo;

    /** 方向：IN 转入，OUT 转出 */
    private String direction;

    /** 变动金额，恒为正数，方向看 direction */
    private BigDecimal amount;

    /** 资金变动时间；BILL 类型与 t_bill.bill_time 一致 */
    private LocalDateTime flowTime;

    /** 备注，转账时可记「银行卡 转 微信零钱」 */
    private String remark;

    /** 逻辑删除：0 正常，1 已删除 */
    @TableLogic
    private Integer deleted;

    /** 创建时间 */
    private LocalDateTime createTime;
}
