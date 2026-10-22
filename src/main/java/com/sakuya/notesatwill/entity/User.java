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
 * 用户表 t_user。
 *
 * <p><b>身份模型（2026-10-13 定稿）</b>：{@code phone} 是唯一的身份标识，
 * 两种登录方式都围绕它展开：
 * <ul>
 *   <li>验证码登录：{@code phone + code}，未注册则<b>自动创建账号</b>（无密码）</li>
 *   <li>密码登录：{@code phone + password}</li>
 * </ul>
 * {@code email} 已退化为<b>可选联系方式</b>，不参与登录、不要求唯一。
 * 本项目<b>不存在"用户名"概念</b>，网上常见的 username 字段已被 phone 取代。
 *
 * <p><b>⚠️ 本类含敏感字段，禁止直接作为接口返回值。</b>
 * 类上用了 {@code @Data}，Jackson 会把 {@code password}（BCrypt 哈希）
 * 一并序列化进响应体。任何对外接口都必须先转成专用 VO 并排除该字段，
 * 不要图省事直接 {@code Result.success(user)}。
 * （这条纪律与 JwtUtil 上"禁止类级 @Getter"同源，只是场景不同。）
 *
 * <p>会员体系为<b>终身制</b>，不设到期时间（用户已明确）。
 * 一期只预留 {@code memberLevel} 字段与权限校验位置，不做支付。
 * 若将来要改订阅制，补一个 {@code member_expire_time} 字段即可，
 * 判定条件改为「等级=1 且 到期时间 &gt; NOW()」。
 *
 * @author sakuya
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_user")
public class User implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 手机号：**唯一的身份标识**，验证码登录与密码登录都用它 */
    private String phone;

    /**
     * BCrypt 加密后的密码（禁止存明文）。
     *
     * <p><b>可为 null</b>：验证码登录会自动注册，这类用户从未设过密码，
     * 之后可通过 {@code POST /api/auth/reset-password} 补设（走手机验证码，无需登录）。
     * 判断"是否已设置密码"时必须同时挡住 null 与空串（用 StringUtils.hasText）。
     */
    private String password;

    /** 昵称：未填时注册流程会自动生成「用户+随机数字」 */
    private String nickname;

    /**
     * 邮箱：**可选的联系方式**，不参与登录、不要求唯一（多个账号可共用）。
     *
     * <p>⚠️ 已知代价：正因为不唯一，「用邮箱找回密码」无法实现
     * （收到邮件的账号可能不止一个，不知道该重置哪个）。
     */
    private String email;

    /** 头像地址 */
    private String avatar;

    /** 账号状态：1 正常，0 禁用 */
    private Integer status;

    /** 会员等级：0 普通，1 终身会员 */
    private Integer memberLevel;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
