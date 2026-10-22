package com.sakuya.notesatwill.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "jwt:blacklist:";


    private final StringRedisTemplate redisTemplate;

    public void blacklist(String jti, long expireAtMillis){
        long ttlMillis = expireAtMillis - System.currentTimeMillis();

        if(ttlMillis <= 0){
            return;
        }

        redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", ttlMillis, TimeUnit.MILLISECONDS);
    }


    public boolean isBlacklisted(String jti){

        try{
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
        }catch (Exception e){
            log.warn("黑名单查询失败，按未拉黑处理: {}", e.getMessage());
            return false;
        }
    }
}
