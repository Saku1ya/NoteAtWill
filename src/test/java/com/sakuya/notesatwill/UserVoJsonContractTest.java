package com.sakuya.notesatwill;

import com.sakuya.notesatwill.entity.DTO.UserVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UserVO 的 JSON 字段名契约测试。
 *
 * <p><b>为什么需要这个测试</b>：{@code isMember} / {@code hasPassword} 这类
 * 布尔字段的 JSON 名，在 record 与 JavaBean 上的行为<b>不一样</b>：
 * <ul>
 *   <li>JavaBean（getter 风格 {@code isXxx()}）→ Jackson 会把 {@code is} 前缀剥掉，
 *       序列化成 {@code "member"}</li>
 *   <li>record（访问器 {@code isMember()}）→ <b>保留原样</b>，序列化成 {@code "isMember"}</li>
 * </ul>
 * 这个差异没有编译期保护，一旦被误改，前端只会拿到 {@code undefined}，
 * 而且不会报任何错——属于典型的"静默失败"。
 *
 * <p>同时锁定 {@code password} 绝不出现在响应里（防越权/泄漏回归）。
 *
 * @author sakuya
 */
@SpringBootTest
class UserVoJsonContractTest {

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void voMustNotLeakPasswordAndMustKeepBooleanFieldNames() {
        UserVO full = new UserVO(1L, "13800138000", "用户123456",
                "http://a.png", "a@b.com", 1, true, true);
        String json = jsonMapper.writeValueAsString(full);

        // ① 绝不能出现密码相关字段
        assertFalse(json.contains("password") && json.contains("\"password\""),
                "UserVO 序列化后不得包含 password 字段，实际：" + json);
        assertFalse(json.contains("$2a$"), "不得出现 BCrypt 哈希，实际：" + json);

        // ② record 的布尔字段保留 is 前缀（与 JavaBean 行为相反）
        assertTrue(json.contains("\"isMember\":true"), "布尔字段名应为 isMember，实际：" + json);
        assertTrue(json.contains("\"hasPassword\":true"), "布尔字段名应为 hasPassword，实际：" + json);

        // ③ 会员等级原值与派生布尔值都在
        assertTrue(json.contains("\"memberLevel\":1"), "应含 memberLevel，实际：" + json);

        // ④ 为 null 的字段应被全局 NON_NULL 配置省略，前端不会拿到一堆 null
        UserVO minimal = new UserVO(2L, "13900139000", "用户999999",
                null, null, 0, false, false);
        String minJson = jsonMapper.writeValueAsString(minimal);
        assertFalse(minJson.contains("\"avatar\""), "null 的 avatar 应被省略，实际：" + minJson);
        assertFalse(minJson.contains("\"email\""), "null 的 email 应被省略，实际：" + minJson);

        // ⑤ 普通用户的两个布尔值应为 false（而不是被省略掉）
        assertTrue(minJson.contains("\"isMember\":false"), "实际：" + minJson);
        assertTrue(minJson.contains("\"hasPassword\":false"), "实际：" + minJson);

        System.out.println("[UserVO 契约] " + json);
        System.out.println("[UserVO 精简] " + minJson);
    }
}
