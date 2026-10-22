package com.sakuya.notesatwill;

import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.service.SmsCodeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 临时验证类：确认 SmsCodeService 的防刷、一次性消费、错误计数行为正确。
 * 验证完毕后可删除。
 */
@SpringBootTest
class SmsCodeCheckTest {

    private static final String PHONE = "13800138000";
    private static final String CODE_KEY = "auth:code:" + PHONE;
    private static final String LIMIT_KEY = "auth:code:limit:" + PHONE;
    private static final String FAIL_KEY = "auth:code:fail:" + PHONE;

    @Autowired
    private SmsCodeService smsCodeService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void verifyAllBehaviors() {
        clean();

        // ---------- ① 正常发送 ----------
        smsCodeService.sendCode(PHONE);
        String code = redisTemplate.opsForValue().get(CODE_KEY);
        assertNotNull(code, "验证码应已写入 Redis");
        System.out.println("① 发码成功 code=" + code
                + "，TTL=" + redisTemplate.getExpire(CODE_KEY) + "秒");

        // ---------- ② 60 秒内重发 → 应被限流 ----------
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> smsCodeService.sendCode(PHONE));
        System.out.println("② 重发被限流，错误码=" + e1.getCode() + "（2017 期望）");

        // ---------- ③ 输错验证码 ----------
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> smsCodeService.verifyCode(PHONE, "000000"));
        System.out.println("③ 错误码被拒，错误码=" + e2.getCode() + "（2015 期望）");

        // ---------- ④ 输错后验证码仍在（「成功才删」的效果） ----------
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(CODE_KEY)),
                "输错后验证码应保留，否则用户要重新发码");
        System.out.println("④ 输错后验证码仍在，失败计数="
                + redisTemplate.opsForValue().get(FAIL_KEY));

        // ---------- ⑤ 正确验证码 → 通过 ----------
        smsCodeService.verifyCode(PHONE, code);
        System.out.println("⑤ 正确验证码通过");

        // ---------- ⑥ 验证码已被消费 ----------
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(CODE_KEY)),
                "校验通过后验证码应被删除");
        System.out.println("⑥ 验证码已消费（Redis 中已不存在）");

        // ---------- ⑦ 重放同一个验证码 → 应失败 ----------
        BusinessException e3 = assertThrows(BusinessException.class,
                () -> smsCodeService.verifyCode(PHONE, code));
        System.out.println("⑦ 重放被拒，错误码=" + e3.getCode() + "（2016 期望）");

        // ---------- ⑧ 连错 5 次 → 验证码作废 ----------
        redisTemplate.delete(LIMIT_KEY);          // 模拟 60 秒已过，解除限流
        smsCodeService.sendCode(PHONE);
        assertNotNull(redisTemplate.opsForValue().get(CODE_KEY), "应重新发码成功");

        for (int i = 1; i <= 5; i++) {
            int round = i;
            assertThrows(BusinessException.class,
                    () -> smsCodeService.verifyCode(PHONE, "000000"),
                    "第 " + round + " 次错误应被拒绝");
        }
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(CODE_KEY)),
                "连错 5 次后验证码应被作废");
        System.out.println("⑧ 连错 5 次后验证码已作废");

        clean();
        System.out.println("\n全部断言通过 ✓");
    }

    private void clean() {
        redisTemplate.delete(CODE_KEY);
        redisTemplate.delete(LIMIT_KEY);
        redisTemplate.delete(FAIL_KEY);
    }
}
