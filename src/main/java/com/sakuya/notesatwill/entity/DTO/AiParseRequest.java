package com.sakuya.notesatwill.entity.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 前端发起 AI 解析的请求。
 *
 * <p><b>只有 {@code text} 一个字段</b>，其余全部由服务端补齐：
 * <ul>
 *   <li>{@code userId} —— 从 token 取（让前端传就能给别人的账本解析）</li>
 *   <li>分类候选 / 账户候选 —— 服务端按当前用户查，<b>不能让前端传</b>，
 *       否则可以传入别人的分类 id 来探测数据</li>
 *   <li>{@code now} —— 服务端时钟</li>
 * </ul>
 *
 * @param text 用户原文，例如"昨天中午吃饭25，打车18"
 * @author sakuya
 */
public record AiParseRequest(
        @NotBlank(message = "请输入要解析的内容")
        @Size(max = 500, message = "输入不能超过 500 个字符")
        String text) {
}
