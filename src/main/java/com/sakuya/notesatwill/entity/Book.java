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
 * 账本表 t_book。
 *
 * <p>多账本的实现核心：{@code bookId} 贯穿 {@code t_bill} 与 {@code t_budget}。
 * 分类与账户是全局共享的，{@code bookId} 只是流水的分组维度，不隔离它们。
 *
 * <p>{@code isDefault = 1} 的默认账本不可删除——用户至少要有一个账本能记账。
 *
 * @author sakuya
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_book")
public class Book implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属用户 */
    private Long userId;

    /** 账本名：日常 / 家庭 / 娱乐… */
    private String name;

    /** 图标标识（前端图标名，不存 emoji） */
    private String icon;

    /** 主题色 */
    private String color;

    /** 是否默认账本：1 是。默认账本不可删除 */
    private Integer isDefault;

    /** 排序值，越小越靠前 */
    private Integer sort;

    /** 逻辑删除：0 正常，1 已删除 */
    @TableLogic
    private Integer deleted;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
