package com.sakuya.notesatwill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 角色预设表 t_persona_preset（系统内置，只读）。
 *
 * <p>与 {@code t_category} 的系统预置数据是同一套思路：
 * 用户选中某个预设后，会<b>复制一份</b>到 {@link AiPersona} 再修改，
 * 系统预设永远不会被改坏。
 *
 * <p><b>本表没有逻辑删除字段</b>：预设是系统资产，不随用户操作变化。
 *
 * @author sakuya
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_persona_preset")
public class PersonaPreset implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 角色名字，如「小小鹦鹉」「记账猫猫」 */
    private String name;

    /** 角色头像地址 */
    private String avatar;

    /** 性别：MALE / FEMALE / OTHER */
    private String gender;

    /** 与用户的关系：朋友 / 家人 / 管家… */
    private String relation;

    /** 对用户的称呼：主人 / 老板… */
    private String address;

    /** 角色设定（性格描述） */
    private String personality;

    /** 排序值 */
    private Integer sort;

    /** 创建时间 */
    private LocalDateTime createTime;
}
