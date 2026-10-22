package com.sakuya.notesatwill;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文加载冒烟测试。
 *
 * <p>验证三件事：
 * <ol>
 *   <li>所有 Bean 能正常装配（含数据源、MyBatis-Plus、Security、Jackson 定制）</li>
 *   <li>application.yaml 的配置能正确绑定
 *       —— 尤其是 Jackson 相关配置，写错枚举常量会在这里直接暴露</li>
 *   <li>能连上 Docker 里的 MySQL 与 Redis</li>
 * </ol>
 *
 * <p><b>前置条件</b>：先启动容器
 * <pre>docker compose up -d</pre>
 * 容器没起来的话这个测试会因为连不上数据源而失败。
 *
 * @author sakuya
 */
@SpringBootTest
class NotesAtWillApplicationTests {

    @Test
    void contextLoads() {
    }

}
