package com.sakuya.notesatwill.entity.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改密码请求（<b>系统内</b>，用户已登录 → {@code PUT /api/user/password}）。
 *
 * <p><b>★ 本 DTO 刻意没有 {@code phone} 字段，这是安全设计，不是遗漏。</b>
 *
 * <p>手机号由 Service 从<b>当前会话（token）</b>对应的用户记录里取。
 * 因为字段根本不存在，前端<b>连"传入别人手机号"这个表达能力都没有</b>——
 * 想改别人的密码在结构上就不可能，比"传了再校验一致性"更彻底：
 * <ul>
 *   <li>运行期校验依赖"记得写这段判断"，漏写或写错分支就失效；</li>
 *   <li>字段不存在则由编译器+序列化保证，没有绕过的余地。</li>
 * </ul>
 *
 * <p><b>与匿名 {@link ResetPasswordRequest} 的分工</b>：
 * <pre>
 *   已登录改密码   PUT  /api/user/password   身份来自 token   ← 本类
 *   忘记密码       POST /api/auth/reset-password  身份来自请求体手机号
 * </pre>
 * 两者的<b>凭据相同</b>（都是短信验证码）、Service 核心流程也只有一份，
 * 但因为<b>身份来源不同</b>，所以在 Controller 层分了两个入口。
 * DTO 里放不放 {@code phone}，正是这个差异的体现。
 *
 * <p>⚠️ 前端注意：这里的验证码要用<b>当前登录账号自己的手机号</b>去申请，
 * 该手机号可从 {@code GET /api/user/me} 取得，不要让用户手输——
 * 手输一旦写错，用户会先收到验证码、提交后才失败，非常费解。
 *
 * @param code        短信验证码（发往当前登录账号绑定的手机号）
 * @param newPassword 新密码
 * @author sakuya
 */
public record ChangePasswordRequest(
        @NotBlank(message = "验证码不能为空")
        @Pattern(regexp = "^\\d{6}$", message = "验证码格式不正确")
        String code,

        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 32, message = "密码长度需在 6-32 位之间")
        String newPassword) {
}
