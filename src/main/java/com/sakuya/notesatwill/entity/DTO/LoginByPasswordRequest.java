package com.sakuya.notesatwill.entity.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 密码登录请求。
 *
 * <p><b>账号名是手机号</b>——手机号是本项目唯一的身份标识，
 * 验证码登录与密码登录都围绕它。
 */
public record LoginByPasswordRequest(
        @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone,
        @NotBlank @Size(min = 6, max = 32, message = "密码长度需在 6-32 位之间") String password) {
}
