package com.sakuya.notesatwill.controller;

import com.sakuya.notesatwill.common.result.Result;
import com.sakuya.notesatwill.entity.DTO.LoginByCodeRequest;
import com.sakuya.notesatwill.entity.DTO.LoginByPasswordRequest;
import com.sakuya.notesatwill.entity.DTO.LoginResponse;
import com.sakuya.notesatwill.entity.DTO.RegisterRequest;
import com.sakuya.notesatwill.entity.DTO.ResetPasswordRequest;
import com.sakuya.notesatwill.entity.DTO.SendCodeRequest;
import com.sakuya.notesatwill.security.JwtAuthenticationFilter;
import com.sakuya.notesatwill.service.AuthService;
import com.sakuya.notesatwill.service.SmsCodeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：注册、登录、登出。
 *
 * <p><b>必须是 {@code @RestController}，不能是 {@code @Controller}。</b>
 * 后者需要每个方法再手写 {@code @ResponseBody} 才会序列化成 JSON；
 * 漏写的话返回值会被当成视图名去解析，报 "Could not resolve view"，
 * 而这个错误信息完全指不到真正的原因。
 *
 * <p><b>本类的方法一律不加 {@code @ResponseStatus}</b>：
 * 业务失败（密码错、验证码错）都返回 HTTP 200 + {@code Result.code}，
 * 由 {@code GlobalExceptionHandler} 统一转换。
 * 一旦在这里加了状态码，前端拦截器就要同时判 HTTP 状态和业务码两层。
 *
 * <p><b>放行清单在 {@code SecurityConfig} 里维护</b>，
 * 本类的 {@code /register}、{@code /send-code}、{@code /login/**} 都是 permitAll；
 * 只有 {@code /logout} 要求已登录——它依赖 Filter 写进 request 的 jti 与过期时间，
 * 未登录时这两个 attribute 根本不存在。
 *
 * @author sakuya
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SmsCodeService smsCodeService;

    /**
     * 发送验证码。
     *
     * <p><b>响应体里绝对不能回传验证码本身</b>，否则"持有该手机号"这个前提就作废了。
     * 调试请看 {@code SmsCodeService} 里的「模拟短信」日志。
     *
     * <p>未注册的手机号也可以发送——验证码登录时会自动注册。
     * 反过来，如果这里对未注册手机号报"用户不存在"，
     * 就等于提供了一个枚举已注册手机号的接口。
     *
     * <p>返回 {@code Result<Void>} 而非把 code 带出去：成功即表示已发送。
     * 60 秒内重复发送会由 {@code SmsCodeService} 抛
     * {@code CODE_SEND_TOO_FREQUENT(2017)}。
     */
    @PostMapping("/send-code")
    public Result<Void> sendCode(@Valid @RequestBody SendCodeRequest request) {
        smsCodeService.sendCode(request.phone());
        return Result.success();
    }

    /**
     * 注册。
     *
     * <p><b>注册成功即视为登录</b>，所以返回 {@code LoginResponse}（带 token）而不是 {@code Void}。
     * 前端注册完可以直接进首页，不必再调一次登录。
     */
    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(authService.register(request));
    }

    /**
     * 密码登录：手机号 + 密码。
     *
     * <p>账号不存在、密码错误、未设置密码三种情况<b>统一返回
     * {@code PASSWORD_ERROR(2012)}「手机号或密码错误」</b>，
     * 刻意不区分——否则这个接口就成了「查询某手机号是否已注册」的工具。
     * 未设置密码的用户靠 {@code /api/user/me} 的 {@code hasPassword=false} 引导去设置。
     */
    @PostMapping("/login/password")
    public Result<LoginResponse> loginByPassword(@Valid @RequestBody LoginByPasswordRequest request) {
        return Result.success(authService.loginByPassword(request));
    }

    /**
     * 验证码登录：手机号 + 验证码，<b>未注册则自动创建账号</b>。
     *
     * <p>自动注册的用户 {@code password} 为 NULL，昵称自动生成为「用户+随机数字」。
     *
     * <p><b>为什么用 POST 而不是 GET</b>：验证码是凭据，
     * 出现在 URL 里会被浏览器历史、代理日志、Referer 头记录下来。
     *
     * <p><b>抽成 {@code /login/code} 而不是与密码登录共用一个 {@code /login}</b>：
     * 两者的请求体字段完全不同（code vs password），
     * 合成一个大 DTO 会让校验注解无法按分支生效
     * （比如密码登录时 {@code code} 的 {@code @NotBlank} 会误报）。
     */
    @PostMapping("/login/code")
    public Result<LoginResponse> loginByCode(@Valid @RequestBody LoginByCodeRequest request) {
        return Result.success(authService.loginByCode(request));
    }

    /**
     * 重置密码（<b>忘记密码场景</b>，用户未登录）。
     *
     * <p><b>这个接口刻意匿名可访问</b>：用户忘记密码时没有登录态，
     * token 不存在，唯一能证明"账号是我的"的就是该手机号的短信验证码，
     * 所以手机号只能由用户自己输入。
     *
     * <p><b>匿名可访问为什么不危险</b>：
     * <ol>
     *   <li>验证码是充分凭据。没有验证码，攻击者连"查用户"这一步都走不到；</li>
     *   <li>Service 层<b>先验码、再查用户</b>，因此无法用本接口探测手机号是否已注册
     *       （这是账号枚举漏洞的典型入口）；</li>
     *   <li>验证码本身有 60 秒限流与连错 5 次作废，暴力枚举不现实。</li>
     * </ol>
     *
     * <p><b>已登录用户改密码请用 {@code PUT /api/user/password}</b>，
     * 那个接口的身份来自 token、DTO 里没有手机号字段，
     * 从结构上就不可能指向别人的账号。两者凭据相同、Service 核心逻辑共用一份，
     * 分开只是为了让「身份从哪来」这件事清晰。
     *
     * <p><b>为什么用 POST 而不是 PUT</b>：本接口是匿名的一次性提交动作，
     * 没有可标识的资源路径。
     *
     * <p>⚠️ 前端注意：如果用户是刚通过验证码登录的（尤其自动注册场景），
     * 60 秒内再点"发送验证码"会拿到 {@code CODE_SEND_TOO_FREQUENT(2017)}，
     * 因为登录发码与重置发码共用同一个限流配额。这是限流正常工作，
     * 前端应做按钮倒计时（"XX 秒后重新获取"）。
     */
    @PostMapping("/reset-password")
    public Result<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return Result.success();
    }

    /**
     * 登出：把当前 token 加入 Redis 黑名单，使其立即失效。
     *
     * <p><b>jti 与过期时间用 {@code @RequestAttribute} 取</b>，
     * 它们由 {@code JwtAuthenticationFilter} 认证成功时写进 request。
     * 常量引用 {@link JwtAuthenticationFilter#ATTR_JTI} / {@link JwtAuthenticationFilter#ATTR_EXP}，
     * <b>不要硬编码字符串</b>——拼错时 {@code @RequestAttribute} 会抛
     * "Missing request attribute"，而报错只提属性名，不提示你是不是拼错了。
     *
     * <p><b>登出只把"这一个"token 拉黑</b>，不是让该用户所有设备下线：
     * JWT 是无状态的，其它设备上已签发的 token 仍然有效，最长 7 天后自然过期。
     * 若要实现"全端下线"，需要额外记录签发时间并在过滤器里比对。
     *
     * <p>TTL 由 {@code TokenBlacklistService} 按「过期时间 - 当前时间」算出，
     * token 本身已过期时不占 Redis 空间。
     */
    @PostMapping("/logout")
    public Result<Void> logout(
            @RequestAttribute(JwtAuthenticationFilter.ATTR_JTI) String jti,
            @RequestAttribute(JwtAuthenticationFilter.ATTR_EXP) long expireAtMillis) {
        authService.logout(jti, expireAtMillis);
        return Result.success();
    }
}
