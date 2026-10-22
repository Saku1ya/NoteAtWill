package com.sakuya.notesatwill;

import com.sakuya.notesatwill.entity.DTO.BookRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code BookRequest.color} 的取值约束回归测试。
 *
 * <p>把"哪些值算合法"钉死成断言，防止以后有人顺手把正则改宽或改窄。
 * 正则这种东西靠肉眼 review 很容易看漏，必须有测试兜着。
 *
 * <p>重点确认三件事：
 * <ol>
 *   <li>{@code null} 是否被 {@code @Pattern} 放行（字段可选）—— 若不放行，color 就成了必填项</li>
 *   <li>空串 {@code ""} 是否被拒绝 —— 前端表单最容易传空串</li>
 *   <li>{@code #RGB} / {@code #RRGGBB} 是否都接受；非法值与 CSS 注入尝试是否都被拒</li>
 * </ol>
 *
 * <p>不依赖 Spring 上下文，直接用 Jakarta Validator，跑得快。
 *
 * @author sakuya
 */
class BookRequestColorValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    /** 取 color 字段上的校验失败；返回 null 表示通过。 */
    private String colorError(String color) {
        BookRequest request = new BookRequest("日常", "wallet", color, 1);
        Set<ConstraintViolation<BookRequest>> violations = validator.validate(request);
        return violations.stream()
                .filter(v -> "color".equals(v.getPropertyPath().toString()))
                .map(ConstraintViolation::getMessage)
                .findFirst()
                .orElse(null);
    }

    @Test
    void colorConstraintSemantics() {
        System.out.println("\n========== color 约束实测 ==========");

        // ① null 应放行（@Pattern 视 null 为合法）
        String r1 = colorError(null);
        System.out.printf("  null         -> %s%n", r1 == null ? "通过" : "拒绝: " + r1);
        assertEquals(null, r1, "null 应被放行，否则 color 就成了必填字段");

        // ② 合法：#RRGGBB（大小写都接受）
        for (String ok : new String[]{"#409EFF", "#409eff", "#AbC123"}) {
            String r = colorError(ok);
            System.out.printf("  %-11s -> %s%n", ok, r == null ? "通过" : "拒绝: " + r);
            assertEquals(null, r, ok + " 应为合法值");
        }

        // ③ 合法：#RGB
        for (String ok : new String[]{"#fff", "#FFF", "#0aF"}) {
            String r = colorError(ok);
            System.out.printf("  %-11s -> %s%n", ok, r == null ? "通过" : "拒绝: " + r);
            assertEquals(null, r, ok + " 应为合法值");
        }

        // ④ 非法值应全部被拒绝
        String[] bad = {
                "",             // 空串（前端易犯）
                "   ",          // 空白
                "409EFF",       // 少了 #（前端易犯）
                "#409EF",       // 5 位，长度非法
                "#409EFFF",     // 7 位，长度非法
                "#GGGGGG",      // 非十六进制字符
                "red",          // 颜色名
                "rgb(64,158,255)",
                "#409EFF; background:url(x)",  // CSS 注入尝试
        };
        for (String b : bad) {
            String r = colorError(b);
            System.out.printf("  %-28s -> %s%n", "\"" + b + "\"", r == null ? "★通过(异常!)" : "拒绝");
            assertFalse(r == null, "非法值应被拒绝: " + b);
        }

        System.out.println("========== 全部符合预期 ==========\n");
    }

    @Test
    void blankColorIsRejectedButNullIsNot() {
        // 把"null 可省、空串不可传"这条契约单独钉死，防止以后误改正则
        assertTrue(colorError(null) == null, "null 应放行");
        assertTrue(colorError("") != null, "空串应拒绝");
    }
}
