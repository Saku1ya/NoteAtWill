package com.sakuya.notesatwill.entity.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 重置密码请求（<b>忘记密码场景</b>，用户未登录 → {@code POST /api/auth/reset-password}）。
 *
 * <p><b>为什么这个接口匿名可访问、手机号必须从请求体来</b>：
 * 用户忘记密码时根本没有登录态，token 不存在，
 * 唯一能证明"这个账号是我的"的东西就是<b>该手机号的短信验证码</b>。
 * 因此手机号只能由用户自己输入，服务端无从事先得知。
 *
 * <p><b>匿名可访问为什么不危险</b>：
 * <ol>
 *   <li>验证码是充分凭据。没有它，攻击者连"查用户"这一步都走不到；</li>
 *   <li>Service 层<b>先验码、再查用户</b>，因此无法用本接口探测手机号是否已注册
 *       （这正是账号枚举漏洞的典型入口）；</li>
 *   <li>验证码有 60 秒限流与连错 5 次作废，暴力枚举不现实。</li>
 * </ol>
 *
 * <p><b>与 {@link ChangePasswordRequest} 的分工</b>：
 * <pre>
 *   忘记密码       POST /api/auth/reset-password  身份来自请求体手机号  ← 本类
 *   已登录改密码   PUT  /api/user/password        身份来自 token
 * </pre>
 * 两者凭据相同、Service 核心逻辑也只有一份，但<b>身份来源不同</b>：
 * 已登录时若还从请求体读手机号，就会出现"身份来自会话、账号却由参数决定"的错位，
 * 已登录用户理论上能指向别人的账号。所以把 {@code phone} 只留给本类
 * （匿名场景），已登录场景用不含该字段的 {@link ChangePasswordRequest}。
 *
 * <p>⚠️ 本接口<b>不自动注册</b>账号（与验证码登录不同）：
 * 忘记密码的前提是账号已存在。
 *
 * @param phone       手机号：本流程唯一的身份标识
 * @param code        短信验证码：本接口唯一的凭据
 * @param newPassword 新密码
 * @author sakuya
 */
public record ResetPasswordRequest(
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        /*
         * 这里可以放心用无条件必填的 @NotBlank / @Pattern：
         * 上一版「条件必填」的反模式（旧密码只在该账号已设过密码时才必填）
         * 已经随验证码方案一起消失，契约重新变成静态的、Swagger 能准确描述的形态。
         */
        @NotBlank(message = "验证码不能为空")
        @Pattern(regexp = "^\\d{6}$", message = "验证码格式不正确")
        String code,

        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 32, message = "密码长度需在 6-32 位之间")
        String newPassword) {
}
