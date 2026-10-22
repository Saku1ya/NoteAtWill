package com.sakuya.notesatwill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sakuya.notesatwill.entity.Book;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住一个容易被误传的行为：<b>MyBatis-Plus 的 {@code .eq(column, null)} 不会跳过该条件。</b>
 *
 * <p><b>为什么值得为它写测试</b>：网上大量资料说"MP 的 eq 遇到 null 会自动忽略条件"，
 * 这个说法在本项目实测下<b>不成立</b>：
 * <pre>
 *   new LambdaQueryWrapper&lt;Book&gt;().eq(Book::getId, 1L).eq(Book::getUserId, null)
 *   → getSqlSegment() = "(id = ? AND user_id = ?)"，参数 user_id 为 null
 * </pre>
 * 即 SQL 里仍会出现 {@code user_id = null}。
 *
 * <p>这个事实直接决定 {@code BookService.getOwned} 里"参数判空"的<b>理由该怎么写</b>：
 * <ul>
 *   <li>若条件被跳过 → 归属校验被抹掉，属<b>静默越权</b>，判空是安全补丁</li>
 *   <li>实际条件保留 → {@code = NULL} 恒不成立、查不到行，属<b>失败关闭</b>，
 *       判空只是"更早失败"的可读性改进</li>
 * </ul>
 * 两者结论完全不同，所以这里用断言把真实行为固定下来；
 * 将来若升级 MP 版本改变了该行为，本测试会立刻失败并提醒复核那段注释。
 *
 * @author sakuya
 */
@SpringBootTest
class MybatisNullEqBehaviorTest {

    @Test
    void nullEqKeepsTheConditionInsteadOfSkippingIt() {
        LambdaQueryWrapper<Book> wrapper = new LambdaQueryWrapper<Book>()
                .eq(Book::getId, 1L)
                .eq(Book::getUserId, null);

        String sql = wrapper.getSqlSegment();
        System.out.println("\n[MP 实测] SQL 片段 = " + sql);

        // ① id 条件在
        assertTrue(sql.contains("id ="), "应包含 id 条件，实际：" + sql);
        // ② 关键断言：user_id 条件【没有被跳过】
        assertTrue(sql.contains("user_id ="),
                "★ user_id 条件被跳过了！这与本项目注释（getOwned）的结论矛盾，"
                        + "归属校验可能被静默绕过，必须复核。实际 SQL：" + sql);
        // ③ null 是作为参数传入的，而不是条件消失
        assertTrue(wrapper.getParamNameValuePairs().containsValue(null),
                "null 应作为参数保留，实际参数：" + wrapper.getParamNameValuePairs());

        System.out.println("[MP 实测] 参数 = " + wrapper.getParamNameValuePairs());
        System.out.println("[MP 实测] 结论：条件保留、null 作为参数 → \"= NULL\" 恒不成立 → 失败关闭 ✓\n");
    }
}
