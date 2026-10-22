package com.sakuya.notesatwill.controller;

import com.sakuya.notesatwill.common.result.Result;
import com.sakuya.notesatwill.entity.DTO.ChangePasswordRequest;
import com.sakuya.notesatwill.entity.DTO.UserVO;
import com.sakuya.notesatwill.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户信息接口（<b>本类下所有接口都需要登录</b>）。
 *
 * <p>路径与认证接口分开：{@code /api/auth/**} 管"我是谁"（注册/登录/登出/忘记密码），
 * {@code /api/user/**} 管"我自己的资料"。
 *
 * <p><b>关于改密码为什么分在两个 Controller</b>：
 * <pre>
 *   PUT  /api/user/password        需登录，身份来自 token      ← 本类
 *   POST /api/auth/reset-password  匿名，身份来自请求体手机号   ← AuthController
 * </pre>
 * 两者凭据相同（短信验证码）、Service 核心逻辑也只有一份，
 * 但<b>身份来源不同</b>，所以在适配层分开。
 * 「身份从哪来」由 Controller 决定，是它该承担的职责。
 *
 * @author sakuya
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;

    /**
     * 查询当前登录用户信息。
     *
     * <p>返回的是 {@link UserVO} 而不是 {@code User} 实体——
     * 实体上带 {@code password}（BCrypt 哈希），直接返回会把它序列化进响应体。
     * 详见 {@code UserVO} 的类注释。
     *
     * <p>本接口不需要在 {@code SecurityConfig} 单独放行：
     * 未登录访问会走 {@code anyRequest().authenticated()}，
     * 由 {@code authenticationEntryPoint} 返回统一 Result 结构的 401
     * （错误码 2002），前端据此跳登录页。
     *
     * <p>{@code userId} 由 {@code @AuthenticationPrincipal} 从 SecurityContext 取，
     * 而 SecurityContext 是 {@code JwtAuthenticationFilter} 填充的——
     * 也就是说 <b>身份只来自 token</b>，前端无法伪造成别人。
     */
    @GetMapping("/me")
    public Result<UserVO> me(@AuthenticationPrincipal Long userId) {
        return Result.success(authService.getCurrentUser(userId));
    }

    /**
     * 修改密码（<b>系统内，用户已登录</b>）。
     *
     * <p><b>★ 手机号不来自请求体，而来自当前会话。</b>
     * {@link ChangePasswordRequest} 里<b>根本没有 phone 字段</b>，
     * 所以前端连"把这次改密指向别的账号"这种表达都做不到——
     * 这比"传了手机号再校验一致性"更彻底：后者依赖运行期判断写对，
     * 前者由类型系统直接封死。
     *
     * <p>验证码要发往<b>当前登录账号自己的手机号</b>（可从
     * {@code GET /api/user/me} 取 {@code phone}）。
     * Service 层是用数据库里的手机号去校验验证码的，
     * 因此即便前端拿别人的号码申请了验证码，在这里也验不过。
     *
     * <p>本接口无需在 {@code SecurityConfig} 放行，走
     * {@code anyRequest().authenticated()}：未登录访问返回统一 401。
     *
     * @param userId  当前登录用户 id（由 token 解析得来，非前端传入）
     * @param request 验证码 + 新密码
     */
    @PutMapping("/password")
    public Result<Void> changePassword(@AuthenticationPrincipal Long userId,
                                       @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(userId, request);
        // 业务响应一律 HTTP 200，成败由 Result.code 表达，不加 @ResponseStatus
        return Result.success();
    }
}
