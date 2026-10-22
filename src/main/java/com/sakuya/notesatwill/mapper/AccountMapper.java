package com.sakuya.notesatwill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sakuya.notesatwill.entity.Account;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 资金账户表 Mapper。
 *
 * <p><b>⚠ 重要纪律</b>：本 Mapper 的 {@code updateById} 能直接改 {@code balance}，
 * 但<b>只允许「资金流水服务」这么用</b>（D2 实现）。
 * 其它地方（如账户改名接口）必须走 {@code AccountService}，
 * 且只更新名称 / 类型 / 图标 / 排序等展示性字段。
 *
 * <p>原因：{@code balance} 是冗余字段，其正确性完全依赖
 * "每次变动都与 {@code t_account_flow} 的一条流水同时发生"。
 * 一旦有代码绕过流水直接改余额，对账公式
 * （{@code balance == initBalance + SUM(IN) - SUM(OUT)}）就会永久性对不上。
 *
 * @author sakuya
 */
@Mapper
public interface AccountMapper extends BaseMapper<Account> {

    /**
     * @param userId 账户归属用户，用于越权防护
     * @param id     账户 id
     * @param delta  变动量：<b>可正可负</b>。
     *               方向为 IN 时传正数，OUT 时传负数（由调用方按 direction 换算）。
     *               注意这里直接做加减法，与 {@code t_account_flow.amount}
     *               "恒为正数 + direction 表方向"的设计不同 ——
     *               流水表要保留方向语义便于阅读，而余额更新只需要净变化量
     * @return 受影响行数：1 表示成功，0 表示账户不存在/不属于该用户/已删除
     */
    @Update("""
            UPDATE t_account
               SET balance = balance + #{delta}
             WHERE id = #{id}
               AND user_id = #{userId}
               AND deleted = 0
            """)
    int addBalance(@Param("userId") Long userId,
                   @Param("id") Long id,
                   @Param("delta") BigDecimal delta);
}
