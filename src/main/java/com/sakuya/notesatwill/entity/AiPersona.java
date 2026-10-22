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
 * 用户 AI 角色设定表 t_ai_persona。
 *
 * <p>这五个字段（{@code name} / {@code gender} / {@code relation} /
 * {@code address} / {@code personality}）会<b>直接拼进系统提示词</b>，
 * 是所有 AI 输出的语气来源。
 *
 * <p>提示词模板建议固化为常量并写单元测试，防止后续误改导致人设崩坏。
 *
 * <p>{@code chatReplyEnabled} 默认 0（记账后 AI 不回复），
 * 一期做单向播报，双向聊天是会员功能（二期）。
 *
 * @author sakuya
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_ai_persona")
public class AiPersona implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属用户 */
    private Long userId;

    /** 基于哪个预设修改而来 */
    private Long presetId;

    /** 角色头像 */
    private String avatar;

    /** 角色名字（界面限制 20 字） */
    private String name;

    /** 性别：MALE / FEMALE / OTHER */
    private String gender;

    /** 与用户的关系 */
    private String relation;

    /** 对用户的称呼 */
    private String address;

    /** 角色设定（性格描述） */
    private String personality;

    /** 记账后 AI 是否回复：1 回复，0 不回复。默认 0 */
    private Integer chatReplyEnabled;

    /** 当前启用：1 启用。一期只允许一个启用 */
    private Integer isActive;

    /** 逻辑删除：0 正常，1 已删除 */
    @TableLogic
    private Integer deleted;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
