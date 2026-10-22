package com.sakuya.notesatwill.security;

import java.io.IOException;
import java.util.List;

import com.sakuya.notesatwill.common.enums.MemberLevel;
import com.sakuya.notesatwill.service.TokenBlacklistService;
import com.sakuya.notesatwill.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 认证过滤器。
 *
 * <p><b>职责边界（重要）</b>：本过滤器只回答「能不能认证」，不回答「要不要拒绝」。
 * <ul>
 *   <li>解析成功 → 往 {@code SecurityContextHolder} 填写认证信息</li>
 *   <li>没带 token / 解析失败 → 记为匿名，<b>继续放行</b></li>
 * </ul>
 * 是否拒绝请求由 {@code SecurityConfig} 的授权规则（{@code authorizeHttpRequests}）决定。
 * 这样「哪些接口需要登录」这件事只在配置里定义一次，过滤器不用重复判断。
 *
 * <p><b>为什么放在 Filter 层而不是 Interceptor</b>：项目引入了完整的
 * {@code spring-boot-starter-security}，其授权过滤器位于过滤器链末尾，
 * 认证必须在那之前完成，否则 {@code SecurityContextHolder} 是空的，所有请求都会被拒。
 *
 * <p><b>注意</b>：本类<b>不加 {@code @Component}</b>。Spring Boot 会自动把容器里
 * 所有 Filter 类型的 bean 注册到 Servlet 容器，若再加上 {@code addFilterBefore}
 * 挂进 Security 链，会导致同一个过滤器<b>执行两次</b>。
 * 所以它只在 {@code SecurityConfig} 里被 {@code new} 出来并手动挂载。
 *
 * @author sakuya
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final TokenBlacklistService blacklistService;

    /**
     * 认证成功后，把当前令牌的 jti 与过期时间写入 request attribute，
     * 供 {@code AuthController.logout} 取出后拉黑。
     *
     * <p><b>为什么公开</b>：Controller 需要用这两个常量去 {@code getAttribute}，
     * 若为 private 则无法引用，只能各自硬编码字符串，容易拼错。
     *
     * <p><b>为什么存时间戳而不是 Date</b>：{@code TokenBlacklistService.blacklist}
     * 接收的是毫秒时间戳，直接存 {@code long} 可免去调用方再转型一次。
     */
    public static final String ATTR_JTI = "jwt.jti";
    public static final String ATTR_EXP = "jwt.exp";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String headerValue = request.getHeader(jwtUtil.getHeaderName());
        String token = jwtUtil.resolveToken(headerValue);

        // 没带 token 不是错误：公开接口本来就不需要，
        // 需要登录的接口会在授权阶段被 AuthorizationFilter 拦下
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtUtil.parseToken(token);

            if(blacklistService.isBlacklisted(claims.getId())){
                log.debug("token 已登出，按匿名处理");
            }else{
                Long userId = jwtUtil.getUserId(claims);
                MemberLevel memberLevel = MemberLevel.of(jwtUtil.getMemberLevel(claims));

                // 权限是「叠加」的：会员首先是普通用户，所以会员同时拥有 ROLE_USER 与 ROLE_MEMBER。
                // 这里直接用 SecurityRole 里预构建的不可变集合，避免每个请求都 new 一遍
                List<GrantedAuthority> authorities = SecurityRole.of(memberLevel.isMember());

                // 必须用三参数构造器。两参数版构造出的 Authentication 是「未认证」状态
                // （isAuthenticated() 返回 false），授权阶段照样会被拒绝 —— 这是最常见的坑。
                // credentials 传 null：JWT 场景没有密码这回事
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                // 供 logout 接口取出后拉黑；EXP 存毫秒时间戳，与黑名单服务的入参类型一致
                request.setAttribute(ATTR_JTI, claims.getId());
                request.setAttribute(ATTR_EXP, claims.getExpiration().getTime());
            }

        } catch (ExpiredJwtException e) {
            // 过期属于正常现象（用户久了没登录），debug 级别即可
            log.debug("token 已过期，按匿名处理：{}", e.getMessage());
        } catch (JwtException | IllegalArgumentException e) {
            // 签名不符 / 格式非法，可能是伪造攻击，warn 级别
            log.warn("token 无效，按匿名处理：{}", e.getMessage());
        }

        // 无论上面走成功还是异常分支，都必须放行：
        // 漏掉这一行，请求会被静默挂住直到超时，且日志里没有任何线索
        filterChain.doFilter(request, response);
    }

    /**
     * 跨域预检请求直接跳过本过滤器。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        return CorsUtils.isPreFlightRequest(request);
    }
}
