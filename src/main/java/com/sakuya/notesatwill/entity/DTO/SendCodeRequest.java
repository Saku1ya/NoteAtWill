package com.sakuya.notesatwill.entity.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 发送验证码请求。
 *
 * <p><b>只有手机号一个字段</b>，且<b>响应里绝不能回传验证码本身</b>——
 * 哪怕开发期也不行。回传就等于把"证明你持有该手机号"这个前提直接作废，
 * 任何人都能拿别人的手机号登录。调试入口是 {@code SmsCodeService} 里的
 * 「模拟短信」日志，不是接口响应。
 *
 * @param phone 手机号（中国大陆），未注册也可发送——验证码登录会自动注册
 * @author sakuya
 */
public record SendCodeRequest(
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone) {
}
