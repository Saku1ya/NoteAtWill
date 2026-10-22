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
 * 分类表 t_category（二级树）。
 *
 * <p>两种数据同表共存，靠 {@code userId} 区分：
 * <ul>
 *   <li>{@code userId == null} → 系统预置，只读，所有用户共享，<b>不可改删</b></li>
 *   <li>{@code userId} 有值 → 用户自建，可改可删</li>
 * </ul>
 *
 * <p><b>为什么没有建 (user_id, parent_id, name) 唯一索引</b>：
 * MySQL 的唯一索引把多行 NULL 视为互不相等，系统预置分类（{@code user_id = NULL}）
 * 根本挡不住重名。且表里有逻辑删除字段，唯一索引会在「删除后重建」时误报冲突。
 * 重名校验统一放在 Service 层做。
 *
 * <p>收入与支出是<b>两棵完全隔离的树</b>：靠 {@code type} 区分，
 * 查询时必须带上 type，否则会把收入和支出的分类混在一起。
 *
 * @author sakuya
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_category")
public class Category implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属用户：NULL 表示系统预置只读分类 */
    private Long userId;

    /** 父分类 id：0 表示一级分类 */
    private Long parentId;

    /** 分类名 */
    private String name;

    /** 图标标识 */
    private String icon;

    /** 收支类型：EXPENSE 支出，INCOME 收入。两棵树完全隔离 */
    private String type;

    /** 排序值 */
    private Integer sort;

    /** 逻辑删除：0 正常，1 已删除 */
    @TableLogic
    private Integer deleted;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
