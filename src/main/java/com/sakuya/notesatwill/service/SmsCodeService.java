package com.sakuya.notesatwill.service;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 *
 * <p><b>Redis key 约定</b>（统一 {@code auth:code:} 前缀，与 {@code jwt:blacklist:} 风格一致）：
 * <ul>
 *   <li>{@code auth:code:<phone>}　　　 验证码本身，TTL 5 分钟</li>
 *   <li>{@code auth:code:limit:<phone>} 发送间隔限流，TTL 60 秒</li>
 *   <li>{@code auth:code:fail:<phone>}　校验失败计数，TTL 5 分钟</li>
 * </ul>
 *
 * @author sakuya
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsCodeService {

    /** 验证码 */
    private static final String CODE_KEY = "auth:code:";

    /** 发送频率限制 */
    private static final String LIMIT_KEY = "auth:code:limit:";

    /** 校验失败次数计数 */
    private static final String FAIL_KEY = "auth:code:fail:";

    /** 验证码有效期（分钟） */
    private static final long CODE_TTL_MINUTES = 5;

    /** 同一手机号两次发送的最小间隔（秒） */
    private static final long SEND_INTERVAL_SECONDS = 60;

    /** 允许的最大连续错误次数，超过则作废本次验证码、强制重新获取 */
    private static final int MAX_FAIL_COUNT = 5;

    /** 手机号格式（中国大陆），与 DTO 上的 @Pattern 保持一致 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    /**
     * 复用同一个 {@link SecureRandom} 实例。
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    /**
     * 发送验证码。
     *
     * <p>步骤顺序是刻意的：先做纯本地的格式校验（快速失败、不消耗配额），
     * 再占用发送频率配额，最后才生成并写库。
     */
    public void sendCode(String phone) {
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }

        checkSendInterval(phone);

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redisTemplate.opsForValue().set(CODE_KEY + phone, code, CODE_TTL_MINUTES, TimeUnit.MINUTES);

        // TODO 接入真实短信服务后删除本行（当前仅用于本地开发调试）
        log.info("【模拟短信】phone={}, code={}", phone, code);
    }

    /**
     * 发送频率限制：同一手机号 {@value #SEND_INTERVAL_SECONDS} 秒内只能发一次。
     *
     * <p><b>必须用 {@code SET NX} 而不是「先查再写」</b>：
     * 后者在并发下会出现「两个请求都查到 key 不存在 → 都写入成功」的竞态，
     * 防刷直接失效。{@code setIfAbsent} 对应 Redis 的
     * {@code SET key value NX EX n}，在 Redis 单线程模型内原子执行，
     * 检查与写入之间没有缝隙。
     */
    private void checkSendInterval(String phone) {
        Boolean firstTime = redisTemplate.opsForValue()
                .setIfAbsent(LIMIT_KEY + phone, "1", SEND_INTERVAL_SECONDS, TimeUnit.SECONDS);

        if (!Boolean.TRUE.equals(firstTime)) {
            throw new BusinessException(ErrorCode.CODE_SEND_TOO_FREQUENT);
        }
    }
    /**
     * 校验验证码。失败一律抛 {@link BusinessException}，不返回布尔值。
     */
    public void verifyCode(String phone, String code) {
        String cached = redisTemplate.opsForValue().get(CODE_KEY + phone);

        if (cached == null) {
            throw new BusinessException(ErrorCode.CODE_EXPIRED);
        }

        if (!cached.equals(code)) {
            recordFailure(phone);
            throw new BusinessException(ErrorCode.CODE_INVALID);
        }

        // 校验通过 → 消费掉验证码，并清空错误计数
        redisTemplate.delete(CODE_KEY + phone);
        redisTemplate.delete(FAIL_KEY + phone);
    }

    /**
     * 记录一次校验失败；达到上限则作废验证码，强制用户重新获取。
     */
    private void recordFailure(String phone) {
        Long fails = redisTemplate.opsForValue().increment(FAIL_KEY + phone);

        // increment 返回 1 说明计数器是新建的，此时才设过期时间。
        // 漏掉这一步会让计数永久累积，用户之后连正确验证码也验不过。
        if (fails != null && fails == 1L) {
            redisTemplate.expire(FAIL_KEY + phone, CODE_TTL_MINUTES, TimeUnit.MINUTES);
        }

        if (fails != null && fails >= MAX_FAIL_COUNT) {
            redisTemplate.delete(CODE_KEY + phone);
            redisTemplate.delete(FAIL_KEY + phone);
            log.warn("验证码连续错误达 {} 次，已作废本次验证码：phone={}", MAX_FAIL_COUNT, phone);
            throw new BusinessException(ErrorCode.CODE_EXPIRED);
        }
    }
}
