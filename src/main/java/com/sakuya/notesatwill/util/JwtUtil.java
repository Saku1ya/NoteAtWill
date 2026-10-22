package com.sakuya.notesatwill.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 工具类：签发与校验 token。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>SecretKey 只在启动时构建一次</b>，缓存在字段里。不要每次调用都 {@code Keys.hmacShaKeyFor(...)}
 *       ——那会重复做字节到密钥的转换，是纯粹的无谓开销。</li>
 *   <li><b>密钥长度校验放在 {@code @PostConstruct}</b>，启动即失败（fail-fast）。
 *       若放到首次调用才检查，问题会推迟到线上第一次登录才暴露。</li>
 *   <li><b>本类只负责「解析出 Claims」</b>，不把 JWT 异常翻译成业务异常。
 *       翻译工作交给过滤器/Service，这样异常语义分层清晰。</li>
 * </ul>
 */
@Slf4j
@Component
public class JwtUtil {

    /**
     * 最短密钥长度
     */
    private static final int MIN_SECRET_BYTES = 32;

    /** 自定义 claim：用户 ID。*/
    public static final String CLAIM_USER_ID = "uid";

    /**
     * 自定义 claim：会员等级（0 普通 / 1 终身会员）。
     *
     * <p><b>为什么等级可以安全地放进 token</b>：本项目会员为<b>终身制</b>，只会升不会降，
     * 所以不存在「旧 token 残留高等级导致越权」的风险 —— 最坏情况只是刚升级的用户
     * 在旧 token 过期前（最长 7 天）还用不到新功能。
     *
     * <p>若将来改成<b>订阅制</b>（会到期降级），这个设计就不再安全：
     * 用户退订后旧 token 里的 MEMBER 等级在过期前依然有效。
     * 届时应改为每请求查库/查缓存读取实时等级。
     *
     * <p>claim 名取 "ml" 而非全称，是为了控制 token 长度（它随每个请求传输）。
     */
    public static final String CLAIM_MEMBER_LEVEL = "ml";

    private final String secret;
    private final long expireSeconds;
    private final String header;
    private final String prefix;

    /** 启动时构建一次，后续复用。 */
    private SecretKey signingKey;
    private JwtParser parser;

    public JwtUtil(@Value("${notes-at-will.jwt.secret}") String secret,
                   @Value("${notes-at-will.jwt.expire-seconds}") long expireSeconds,
                   @Value("${notes-at-will.jwt.header}") String header,
                   @Value("${notes-at-will.jwt.prefix}") String prefix) {
        this.secret = secret;
        this.expireSeconds = expireSeconds;
        this.header = header;
        this.prefix = prefix;
    }

    /**
     * 启动时校验密钥并构建可复用的密钥/解析器。
     *
     * <p>用 {@code @PostConstruct} 而非构造器，是为了让校验失败的错误信息
     * 附带足够的上下文（比如提示用环境变量 JWT_SECRET 覆盖）。
     */
    @PostConstruct
    void init() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(String.format(
                    "JWT 密钥过短：当前 %d 字节，HS256 至少需要 %d 字节。"
                            + "请在配置或环境变量 JWT_SECRET 中换一个足够长的随机字符串。",
                    keyBytes.length, MIN_SECRET_BYTES));
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.parser = Jwts.parser().verifyWith(signingKey).build();
        // 显式打印算法名：JJWT 是按【密钥字节长度】自动选 HS256/384/512 的（>=32/48/64 字节），
        // 换 secret 长度会静默改变算法，记进日志便于排查"为什么旧 token 验不过"。
        log.info("JWT 初始化完成：algorithm={}, secretBytes={}, expire={}s, header={}, prefix='{}'",
                signingKey.getAlgorithm(), keyBytes.length, expireSeconds, header, prefix);
    }

    // ==================== 签发 ====================

    /**
     * 生成 token。
     *
     * @param claims  自定义载荷（如 uid ），可为 null
     * @param subject 主题，这里放 phone
     * @return 紧凑格式的 JWS 字符串
     */
    public String generateToken(Map<String, Object> claims, String subject) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireSeconds * 1000L);

        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(subject)
                .issuedAt(now)
                .expiration(expiry);
        if (claims != null && !claims.isEmpty()) {
            builder.claims().add(claims).and();
        }
        return builder.signWith(signingKey).compact();
    }

    /**
     * 便捷重载：只传 userId 与 phone，等价于普通用户（memberLevel = 0）。
     */
    public String generateToken(Long userId, String phone) {
        return generateToken(userId, phone, null);
    }

    /**
     * 便捷重载：登录成功后签发 token（含会员等级）。
     *
     * <p>登录时已经查出 {@code t_user} 记录，顺手把 {@code memberLevel} 写进 token，
     * 后续每个请求就不必再查一次库来判断会员身份。
     *
     * <p>这里用 {@code HashMap} 而非 {@code Map.of}：后者不允许 null 值，
     * 传 {@code memberLevel = null} 会直接抛 NPE，属于易踩的坑。
     *
     * @param memberLevel 会员等级（0 普通 / 1 终身会员）；传 {@code null} 按 0 处理
     */
    public String generateToken(Long userId, String phone, Integer memberLevel) {
        Map<String, Object> claims = new HashMap<>(4);
        claims.put(CLAIM_USER_ID, userId);
        claims.put(CLAIM_MEMBER_LEVEL, memberLevel == null ? 0 : memberLevel);
        return generateToken(claims, phone);
    }

    // ==================== 解析 ====================

    /**
     * 解析并校验 token，返回 Claims。
     *
     * <p><b>注意</b>：本方法在 token 非法/过期/签名不匹配时【抛出 JwtException】，
     * 不做吞异常处理。调用方（JwtAuthenticationFilter）负责决定如何响应。
     *
     * @return 校验通过的 Claims；任何问题都抛异常，不会返回 null
     * @throws JwtException token 过期、签名错误、格式非法等
     * @throws IllegalArgumentException token 为空
     */
    public Claims parseToken(String token) {
        return parser.parseSignedClaims(token).getPayload();
    }

    /**
     * 尝试解析 token，失败返回 null。
     *
     * <p>适合"有 token 就用，没有或无效就当匿名"的场景（如可选登录接口）。
     * 需要区分失败原因时请用 {@link #parseToken}。
     */
    public Claims parseTokenQuietly(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return parseToken(token);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 解析失败（已忽略）：{}", e.getMessage());
            return null;
        }
    }

    // ==================== 取值便捷方法 ====================

    /** 从 Claims 中取 userId。缺失或类型异常时返回 null。 */
    public Long getUserId(Claims claims) {
        Object raw = claims.get(CLAIM_USER_ID);
        if (raw == null) {
            return null;
        }
        // JSON 反序列化后数字可能是 Integer 或 Long，统一转
        return raw instanceof Number n ? n.longValue() : null;
    }

    /** 从 Claims 中取 username。 */
    public String getPhone(Claims claims) {
        return claims.getSubject();
    }

    /**
     * 从 Claims 中取会员等级。
     *
     * <p>缺失或类型异常一律返回 0（普通用户）。
     *
     * <p>两种会走到这里的正常情况：
     * <ul>
     *   <li><b>旧 token</b>：在加入会员体系之前签发的 token 里没有 {@code ml} claim</li>
     *   <li>等级值为 0 时被序列化掉的场景</li>
     * </ul>
     * 所以「缺失」必须按普通用户处理，不能抛异常 —— 否则升级部署的瞬间，
     * 所有在线用户的旧 token 都会失效。
     */
    public int getMemberLevel(Claims claims) {
        Object raw = claims.get(CLAIM_MEMBER_LEVEL);
        return raw instanceof Number n ? n.intValue() : 0;
    }

    public String getTokenId(Claims claims){
        return claims.getId();
    }

    // ==================== 请求头处理 ====================

    /**
     * 从请求头值中剥离前缀，得到纯 token。
     *
     * @param headerValue 形如 "Bearer eyJhbGci..."
     * @return 纯 token；若头缺失、前缀不匹配则返回 null
     */
    public String resolveToken(String headerValue) {
        if (headerValue == null || !headerValue.startsWith(prefix)) {
            return null;
        }
        String token = headerValue.substring(prefix.length()).trim();
        return token.isEmpty() ? null : token;
    }

    public String getHeaderName() {
        return header;
    }

    public long getExpireSeconds() {
        return expireSeconds;
    }
}
