package com.sakuya.notesatwill.entity.DTO;

import jakarta.validation.constraints.Pattern;

public record CategoryRequest (
        Long parentId,
        String name,
        String icon,
        Integer sort,
        @Pattern(regexp = "^(EXPENSE|INCOME)$", message = "收支类型只能是 EXPENSE 或 INCOME")
        String type
){
}
