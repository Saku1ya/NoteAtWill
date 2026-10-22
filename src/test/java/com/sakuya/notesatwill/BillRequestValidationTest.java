package com.sakuya.notesatwill;

import com.sakuya.notesatwill.entity.DTO.BillRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code BillRequest} 取值约束回归测试。
 *
 * <p>把 DTO 契约钉死成断言，重点四件事：
 * <ol>
 *   <li><b>★ {@code accountId} 必须保持【可选】</b> ——
 *       这是"记账与账户是两个独立功能"的产品模型落点。
 *       若有人顺手给它加 {@code @NotNull}，本测试会失败</li>
 *   <li><b>{@code billTime} 是必填</b>（设计 Y，2026-10-15 定案）——
 *       手动路径的前端总会带值，缺了就该明确拒绝</li>
 *   <li>{@code type} 只接受 EXPENSE / INCOME（用 {@code @Pattern} 收窄取值，
 *       不是只判非空）</li>
 *   <li>{@code amount} 挡住 0 / 负数 / <b>超两位小数</b>
 *       （后者若放行，MySQL 会静默四舍五入）</li>
 * </ol>
 *
 * <p><b>为什么用 Validator 而不是 MockMvc</b>：DTO 约束是纯内存判断，
 * 不需要 Spring 上下文、不需要数据库、不需要登录态，跑起来快得多。
 * 注意这里验证的是<b>边界层</b>的行为；Service 层还有一道独立校验
 * （见 {@code CreateBillTest#nullBillTimeIsRejected}），两者都要有。
 *
 * @author sakuya
 */
class BillRequestValidationTest {

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

    /** 一份全部合法的请求，各用例在它基础上只改一个字段。 */
    private BillRequest valid() {
        return new BillRequest(1L, 1001L, "EXPENSE", new BigDecimal("25.00"),
                LocalDateTime.now(), "午餐", null);
    }

    /** 取指定字段的校验失败信息；返回 null 表示该字段通过。 */
    private String errorOn(BillRequest req, String field) {
        Set<ConstraintViolation<BillRequest>> violations = validator.validate(req);
        return violations.stream()
                .filter(v -> field.equals(v.getPropertyPath().toString()))
                .map(ConstraintViolation::getMessage)
                .findFirst()
                .orElse(null);
    }

    // ==================== ★ 最重要的两条 ====================

    @Test
    void accountIdMustStayOptional() {
        System.out.println("\n===== ★ accountId 必须是【可选】的 =====");

        // accountId 为 null 的请求不应有任何校验失败
        BillRequest noAccount = new BillRequest(1L, 1001L, "EXPENSE",
                new BigDecimal("25.00"), LocalDateTime.now(), "没选账户", null);
        Set<ConstraintViolation<BillRequest>> v = validator.validate(noAccount);

        System.out.println("  accountId=null → 校验失败数=" + v.size());
        v.forEach(x -> System.out.println("    " + x.getPropertyPath() + ": " + x.getMessage()));
        assertEquals(0, v.size(),
                "★★ accountId 绝不能变成必填 —— 那会把『记账』与『账户』两个独立功能重新绑死，"
                        + "与产品设计相反。若本断言失败，说明有人给它加了 @NotNull");
        System.out.println("  ✓ 不选账户是合法请求（纯消费记录，不产生任何资金流水）");

        // 有 accountId 也应通过
        BillRequest withAccount = new BillRequest(1L, 1001L, "EXPENSE",
                new BigDecimal("25.00"), LocalDateTime.now(), "选了账户", 9L);
        assertEquals(0, validator.validate(withAccount).size(), "传了 accountId 也应合法");
        System.out.println("  ✓ 选了账户同样合法");
    }

    @Test
    void billTimeIsRequired() {
        System.out.println("\n===== billTime 必填（设计 Y）=====");

        BillRequest nullTime = new BillRequest(1L, 1001L, "EXPENSE",
                new BigDecimal("25.00"), null, "未传时间", null);
        String msg = errorOn(nullTime, "billTime");

        System.out.println("  billTime=null → " + msg);
        assertNotNull(msg, "★ billTime 为 null 必须被 @NotNull 拦下");
        assertEquals("记账时间不能为空", msg);
        System.out.println("  ✓ 被拒绝，提示语明确");

        // 放进过去（补记）与当前都应该通过
        assertNull(errorOn(new BillRequest(1L, 1001L, "EXPENSE", new BigDecimal("25.00"),
                LocalDateTime.now().minusDays(7), "补记上周", null), "billTime"),
                "补记过去的时间应合法");
        System.out.println("  ✓ 过去时间（补记）合法 —— 只有上界由 Service 校验");
    }

    // ==================== 其余字段 ====================

    @Test
    void typeOnlyAcceptsTwoValues() {
        System.out.println("\n===== type 只接受 EXPENSE / INCOME =====");

        for (String ok : new String[]{"EXPENSE", "INCOME"}) {
            assertNull(errorOn(new BillRequest(1L, 1001L, ok, new BigDecimal("1.00"),
                    LocalDateTime.now(), null, null), "type"), ok + " 应合法");
            System.out.println("  " + ok + " → 通过");
        }

        String[] bad = {"TRANSFER", "expense", "income", "", "   ", "XX", "EXPENSE "};
        for (String b : bad) {
            String msg = errorOn(new BillRequest(1L, 1001L, b, new BigDecimal("1.00"),
                    LocalDateTime.now(), null, null), "type");
            System.out.printf("  %-11s → %s%n", "\"" + b + "\"", msg == null ? "★通过(异常!)" : "拒绝");
            assertNotNull(msg, "非法 type 应被拒绝: " + b);
        }
        System.out.println("  ✓ 大小写敏感、转账类型被拒（转账不属于记账）");
    }

    @Test
    void amountMustBePositiveAndHaveAtMostTwoDecimals() {
        System.out.println("\n===== amount 约束 =====");

        // 合法
        for (String ok : new String[]{"0.01", "25.00", "9999999999.99"}) {
            assertNull(errorOn(new BillRequest(1L, 1001L, "EXPENSE", new BigDecimal(ok),
                    LocalDateTime.now(), null, null), "amount"), ok + " 应合法");
            System.out.println("  " + ok + " → 通过");
        }

        // 非正数：被 @DecimalMin 拒绝
        for (String bad : new String[]{"0", "0.00", "-5.00"}) {
            String msg = errorOn(new BillRequest(1L, 1001L, "EXPENSE", new BigDecimal(bad),
                    LocalDateTime.now(), null, null), "amount");
            System.out.printf("  %-8s → %s%n", bad, msg == null ? "★通过(异常!)" : "拒绝");
            assertNotNull(msg, bad + " 应被拒绝");
        }

        // ★ 超两位小数：必须拒绝。
        //   t_bill.amount 是 DECIMAL(12,2)，若放行 25.555，MySQL 会【静默四舍五入】成 25.56
        //   —— 前端显示 25.555、库里是 25.56，用户对账时会觉得"钱少了 5 厘"。
        for (String bad : new String[]{"25.555", "0.001"}) {
            String msg = errorOn(new BillRequest(1L, 1001L, "EXPENSE", new BigDecimal(bad),
                    LocalDateTime.now(), null, null), "amount");
            System.out.printf("  %-8s → %s%n", bad, msg == null ? "★通过(异常!)" : "拒绝: " + msg);
            assertNotNull(msg, "★ 超两位小数必须拒绝，否则会被静默舍入: " + bad);
        }

        // 超出 DECIMAL(12,2) 的整数位上限（10 位）
        String tooBig = errorOn(new BillRequest(1L, 1001L, "EXPENSE",
                new BigDecimal("99999999999.99"), LocalDateTime.now(), null, null), "amount");
        System.out.println("  99999999999.99 → " + (tooBig == null ? "★通过(异常!)" : "拒绝"));
        assertNotNull(tooBig, "超出 DECIMAL(12,2) 整数位应拒绝");
        System.out.println("  ✓ 非正数与超精度都被拦下");
    }

    @Test
    void bookIdAndCategoryIdAreRequired() {
        System.out.println("\n===== bookId / categoryId 必填 =====");

        assertNotNull(errorOn(new BillRequest(null, 1001L, "EXPENSE", new BigDecimal("1.00"),
                LocalDateTime.now(), null, null), "bookId"), "bookId 必填");
        assertNotNull(errorOn(new BillRequest(1L, null, "EXPENSE", new BigDecimal("1.00"),
                LocalDateTime.now(), null, null), "categoryId"), "categoryId 必填");
        System.out.println("  bookId=null → 拒绝；categoryId=null → 拒绝 ✓（与 t_bill 的 NOT NULL 一致）");
    }

    @Test
    void remarkLengthIsCapped() {
        System.out.println("\n===== remark 长度上限 255 =====");

        assertNull(errorOn(new BillRequest(1L, 1001L, "EXPENSE", new BigDecimal("1.00"),
                LocalDateTime.now(), null, null), "remark"), "remark=null 合法（用户经常不写）");

        String ok255 = "x".repeat(255);
        assertNull(errorOn(new BillRequest(1L, 1001L, "EXPENSE", new BigDecimal("1.00"),
                LocalDateTime.now(), ok255, null), "remark"), "255 字应合法");

        String too256 = "x".repeat(256);
        assertNotNull(errorOn(new BillRequest(1L, 1001L, "EXPENSE", new BigDecimal("1.00"),
                LocalDateTime.now(), too256, null), "remark"), "256 字应拒绝");
        System.out.println("  null / 255 字 → 通过；256 字 → 拒绝 ✓（与 varchar(255) 一致）");
    }

    @Test
    void fullyValidRequestPasses() {
        System.out.println("\n===== 完整合法请求应零违规 =====");
        Set<ConstraintViolation<BillRequest>> v = validator.validate(valid());
        v.forEach(x -> System.out.println("  意外违规: " + x.getPropertyPath() + " " + x.getMessage()));
        assertEquals(0, v.size());
        System.out.println("  ✓ 零违规");
    }
}
