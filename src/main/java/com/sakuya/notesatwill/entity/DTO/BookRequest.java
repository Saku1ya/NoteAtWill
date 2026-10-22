package com.sakuya.notesatwill.entity.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建 / 修改账本请求。
 *
 * <p><b>为什么 POST 与 PUT 共用一个 DTO</b>：账本在两个场景下的可编辑字段
 * 完全一致（名称、图标、颜色、排序），没有"创建时能传、修改时不能传"的字段，
 * 拆成两个类只会增加维护面。
 *
 * <p><b>★ 为什么这里【没有】下面三个字段（这是安全设计，不是遗漏）</b>：
 * <table border="1">
 *   <caption>被刻意排除的字段</caption>
 *   <tr><th>字段</th><th>排除理由</th></tr>
 *   <tr>
 *     <td>{@code userId}</td>
 *     <td>只能从 token 解析。若允许前端传入，就等于允许<b>给别人建账本</b>，
 *         是最典型的越权漏洞</td>
 *   </tr>
 *   <tr>
 *     <td>{@code isDefault}</td>
 *     <td>由服务端决定（只有注册时创建的那个才是默认账本）。
 *         若可传，用户就能把新建的账本"提升"为默认，
 *         从而绕过"默认账本不可删除"的保护，甚至出现两个默认账本</td>
 *   </tr>
 *   <tr>
 *     <td>{@code deleted}</td>
 *     <td>逻辑删除标记，属于内部状态，不应由接口控制</td>
 *   </tr>
 * </table>
 *
 * <p>这正是接口层一律使用 DTO、而不直接接收 {@code Book} 实体的原因。
 *
 * <p><b>关于 {@code color} 为什么要用 {@code @Pattern} 限制格式</b>：
 * 它是"纯展示字段"，后端没有一行逻辑读它，看起来没必要管。
 * 但它在前端会直接进 CSS（例如 {@code :style="{ background: book.color }"}）——
 * 一旦允许任意字符串，最轻是样式错乱，严重些可以做 CSS 注入。
 * 所以仍然要在边界上把值域收窄成颜色格式。
 *
 * <p>这与 {@code CategoryRequest.type}、{@code AccountRequest.type} 用
 * {@code @Pattern} 收窄取值是同一个思路：<b>能枚举/能格式化的字段，就不要留自由文本</b>。
 *
 * <p><b>注意校验语义</b>：{@code @Pattern} 把 {@code null} 视为合法（字段可不传），
 * 但空串 {@code ""} 会被拒绝。前端若想表达"不设颜色"，应省略该字段或传 {@code null}，
 * 不要传空串。
 *
 * @param name  账本名，如"日常"/"家庭"/"娱乐"
 * @param icon  图标标识（前端图标名，不存 emoji）
 * @param color 主题色，需为 {@code #RGB} 或 {@code #RRGGBB} 格式（如 {@code #409EFF}）；可不传
 * @param sort  排序值，越小越靠前。<b>不传时由服务端追加到末尾</b>，
 *              避免新建的账本插到默认账本前面
 * @author sakuya
 */
public record BookRequest(
        @NotBlank(message = "账本名不能为空")
        @Size(max = 50, message = "账本名不能超过 50 个字符")
        String name,

        @Size(max = 50, message = "图标标识不能超过 50 个字符")
        String icon,

        /*
         * 不写 @Size：正则本身已经把长度钉死在 7 字符以内（#RRGGBB），
         * 再加长度校验是冗余的，反而让人以为两者都在起作用。
         */
        @Pattern(regexp = "^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6})$",
                message = "主题色需为 #RGB 或 #RRGGBB 格式，如 #409EFF")
        String color,

        Integer sort) {
}
