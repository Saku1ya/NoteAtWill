package com.sakuya.notesatwill.entity.DTO.ai;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发往 Python 服务 {@code POST /ai/parse-bill} 的请求体。
 *
 * <p><b>★ 这是"外部契约"，与前端用的 DTO 严格分开</b>：
 * 本包（{@code entity.DTO.ai}）下的类只描述"Java ↔ Python"的线上格式，
 * 不对前端暴露、也不允许前端直接传。两者混用会让"不可信数据"和"自家契约"
 * 共用一套校验规则，安全边界就模糊了。
 *
 * <p><b>★ {@code now} 为什么由 Java 提供而不是让 Python 自己取时钟</b>：
 * "今天是几号"必须只有一个来源。若两边时钟或时区不同，"昨天"会被算到不同日期，
 * 而且这种错只在跨时区时才暴露，极难复现。
 *
 * <p><b>★ 时间格式必须显式指定 ISO-8601（带 T）</b>：
 * 本项目的全局 Jackson 配置（{@code JacksonConfig}）把 {@code LocalDateTime}
 * 绑定成 {@code "yyyy-MM-dd HH:mm:ss"}（空格分隔），而 Python 的 Pydantic
 * 输出的是 ISO 格式 {@code "2026-10-17T12:00:00"}。两者不兼容，
 * 所以**在本字段上单独覆盖格式**，让跨服务协议统一走 ISO 标准。
 *
 * @param text       用户原文
 * @param now        当前时间（Java 提供，ISO-8601 带 T）
 * @param categories 该用户可见的分类候选
 * @param accounts   该用户的账户候选（只用于给模型提示名字）
 * @author sakuya
 */
public record PythonParseRequest(
        String text,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime now,

        List<Category> categories,
        List<Account> accounts) {

    /**
     * 分类候选。
     *
     * <p>带 {@code type} 是为了让模型能自检"支出不能配收入分类"——
     * 数据库层面没有这个约束，只能靠 Python 侧和这里一起把关。
     *
     * @param id   分类 id，模型只能从这个集合里选
     * @param name 展示名，二级分类形如「餐饮/午餐」
     * @param type EXPENSE / INCOME
     */
    public record Category(Long id, String name, String type) {
    }

    /**
     * 账户候选。
     *
     * <p><b>★ {@code id} 发给模型仅作参考，Prompt 里明确要求它不要输出 id。</b>
     * 原因：模型对"微信""招行"这类口语词的匹配不可靠，
     * 强行让它输出 accountId 必然产生幻觉（指向不存在或别人的账户）。
     * 真正的账户匹配放在前端预览界面由用户确认。
     *
     * @param id   账户 id
     * @param name 账户名
     */
    public record Account(Long id, String name) {
    }
}
