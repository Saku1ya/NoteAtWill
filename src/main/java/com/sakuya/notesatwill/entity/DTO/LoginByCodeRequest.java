package com.sakuya.notesatwill.entity.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginByCodeRequest(
        @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String phone,
        @NotBlank @Pattern(regexp = "^\\d{6}$") String code) {
}
