package com.sakuya.notesatwill.entity.DTO;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String phone,
        @NotBlank @Pattern(regexp = "^\\d{6}$") String code,
        @NotBlank @Size(min = 6,max = 32) String password,
        @Size(max = 20) String nickname,
        @Email String email) {
}
