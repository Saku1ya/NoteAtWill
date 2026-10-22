package com.sakuya.notesatwill.entity.DTO;

public record LoginResponse(
        String token,
        long expiresIn) {
}
