package com.sakuya.notesatwill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * CORS 跨域配置。
 *
 * <p>前端 Vue 开发服务器（通常 5173 / 3000 端口）与本服务（8080）不同源，
 * 浏览器会拦截请求，所以必须显式放行。
 *
 * <p><b>为什么用 CorsConfigurationSource 而不是 @CrossOrigin</b>：
 * 在 Spring Security 项目里，CORS 必须在 Security 过滤器链<b>之前</b>生效，
 * 否则预检请求（OPTIONS）会先被 Security 拦下返回 401。
 * 提供一个 {@code CorsConfigurationSource} Bean 后，
 * SecurityConfig 里的 {@code .cors(Customizer.withDefaults())} 就能自动接上。
 *
 * <p><b>生产环境要收紧</b>：把 allowedOriginPatterns 改成真实域名，
 * 不要保留 "*"。
 *
 * @author sakuya
 */
@Configuration
public class CorsConfig {

    /**
     * CORS 规则源。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // 用 allowedOriginPatterns 而不是 allowedOrigins：
        // 前者支持通配且允许与 allowCredentials=true 共存，后者不允许。
        config.setAllowedOriginPatterns(List.of("*"));

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));

        // 放行所有请求头，否则前端带的 Authorization 会被拦掉
        config.setAllowedHeaders(List.of("*"));

        // 允许携带凭证（Cookie / Authorization）
        config.setAllowCredentials(true);

        // 预检请求结果缓存 1 小时，减少 OPTIONS 请求次数
        config.setMaxAge(3600L);

        // 暴露给前端的响应头（前端要读 Content-Disposition 时用得上）
        config.setExposedHeaders(List.of("Content-Disposition"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
