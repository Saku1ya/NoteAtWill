package com.sakuya.notesatwill.config;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.security.JwtAuthenticationFilter;
import com.sakuya.notesatwill.service.TokenBlacklistService;
import com.sakuya.notesatwill.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

/**
 * Spring Security 配置。
 *
 * <p><b>为什么必须有这个类</b>：只要 classpath 上有 spring-boot-starter-security，
 * Spring Boot 就会自动开启<b>全站认证拦截</b>并生成一个随机密码
 * （启动日志里的 "Using generated security password: xxxx"）。
 * 那会导致所有接口都返回 401，且密码每次启动都变，无法用于测试。
 *
 * <p><b>本项目采用 JWT + 无状态认证</b>，所以：
 * <ul>
 *   <li>{@code csrf} 关闭 —— 无状态 API 不需要 CSRF token</li>
 *   <li>{@code sessionCreationPolicy = STATELESS} —— 不创建 HttpSession，服务端不存登录态</li>
 *   <li>{@code formLogin} / {@code httpBasic} 关闭 —— 不用默认登录页</li>
 * </ul>
 *
 * <p><b>阶段 1 待办</b>：这里的 {@code permitAll} 是开发期的宽松配置。
 * 等认证模块做好后，要改成：
 * <pre>
 *   .requestMatchers("/api/auth/**", "/actuator/health").permitAll()
 *   .anyRequest().authenticated()
 * </pre>
 * 并加上 JWT 过滤器。
 *
 * @author sakuya
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 密码编码器。
     *
     * <p>用 BCrypt 而非 MD5/SHA —— BCrypt 自带盐值且计算成本可调，
     * 能显著提高暴力破解成本。数据库中存的必须是 BCrypt 结果（见 t_user.password 注释）。
     *
     * <p><b>为什么定义成 Bean 而不是各处 new</b>：保证全项目用同一套加密参数。
     * 如果以后要调整强度，只改这里一处。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 安全过滤器链。
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtUtil jwtUtil,
                                           TokenBlacklistService tokenBlacklistService) throws Exception {
        // 手动 new 出来再挂进链里，而不是交给 Spring 自动注册：
        // JwtAuthenticationFilter 上不加 @Component，否则会被注册两次（执行两遍）
        JwtAuthenticationFilter jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtUtil,tokenBlacklistService);

        http
                // 无状态 API，不需要 CSRF 防护
                .csrf(csrf -> csrf.disable())
                // 不用默认登录页与 HTTP Basic
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                // 不创建 Session，登录态完全靠 JWT
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 允许 CORS（前端开发期跨域访问需要）
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        // ------------------------------------------------------------------
                        // ★ 顺序即优先级：自上而下匹配，命中第一条就停止。
                        //   因此 anyRequest() 必须写在最后，否则会吞掉它之后的所有规则，
                        //   典型症状是「连登录接口都返回 401，永远登不进去」。
                        // ------------------------------------------------------------------

                        // 关于 login 的两个路径（都保留，避免拆接口时再踩一次）：
                        //   "/api/auth/login"     单接口写法（请求体里区分登录方式）
                        //   "/api/auth/login/**"  拆成 /login/password 与 /login/code 时的写法
                        // 注：PathPatternParser 下 /** 本身就能匹配到 /api/auth/login，
                        //     这里仍然显式列出前者，是为了让"支持哪几种登录"一眼可见。
                        .requestMatchers(
                                "/api/auth/register",
                                "/api/auth/send-code",
                                // 重置密码：刻意匿名。它同时服务"已登录改密码"与
                                // "忘记密码"两种场景，两者的充要条件都是"能收到短信验证码"，
                                // token 在其中是冗余的。安全性由 Service 层的
                                // "先验码、再查用户"保证，不依赖登录态。
                                "/api/auth/reset-password",
                                "/api/auth/login",
                                "/api/auth/login/**"
                        ).permitAll()

                        // 跨域预检：OPTIONS 请求不携带 Authorization 头
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // 探活与错误转发
                        .requestMatchers("/actuator/health", "/error").permitAll()

                        // ------------------------------------------------------------------
                        // 会员专享接口示例（一期无实际会员功能，仅留写法）
                        //   ① 路径规则：集中在此处，便于总览哪些接口是会员专属
                        //      .requestMatchers("/api/ai/chat/**").hasRole("MEMBER")
                        //   ② 方法注解：粒度更细，权限声明跟接口写在一起
                        //      @PreAuthorize("hasRole('MEMBER')")
                        //      —— 依赖类上的 @EnableMethodSecurity，已开启
                        // 注：hasRole("MEMBER") 会自动匹配 ROLE_MEMBER，
                        //     不要再写成 hasRole("ROLE_MEMBER")（前缀会加两遍）
                        // ------------------------------------------------------------------

                        // 兜底：其余一切都要登录
                        .anyRequest().authenticated()
                )
                // 把 JWT 认证过滤器挂进链里。
                // 锚点选 UsernamePasswordAuthenticationFilter：它在链中处于「认证处理」的位置，
                // 挂到它之前即可保证认证发生在末尾的 AuthorizationFilter（授权决策）之前。
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // 未认证 / 无权限时返回统一 Result 结构，而不是 Security 默认的 JSON
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            log.warn("未认证访问 | {} {}", request.getMethod(), request.getRequestURI());
                            writeResult(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            log.warn("无权限访问 | {} {}", request.getMethod(), request.getRequestURI());
                            writeResult(response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN);
                        })
                );

        return http.build();
    }

    /**
     * 把统一 Result 结构写进响应体。
     *
     * <p>Security 的异常发生在 Spring MVC 的 DispatcherServlet <b>之前</b>，
     * 所以 {@code GlobalExceptionHandler} 拦不到它们，
     * 必须在这里手动写响应，才能保证前端拿到的一直是同一种 JSON 结构。
     */
    private void writeResult(jakarta.servlet.http.HttpServletResponse response,
                             HttpStatus status,
                             ErrorCode errorCode) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 这里直接手拼 JSON，避免在 Security 的早期阶段依赖 ObjectMapper 的可用性
        String body = String.format(
                "{\"code\":%d,\"message\":\"%s\"}",
                errorCode.getCode(), errorCode.getMessage());
        response.getWriter().write(body);
    }
}
