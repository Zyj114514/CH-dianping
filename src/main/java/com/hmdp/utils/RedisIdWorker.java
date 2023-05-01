package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP = 1640995200L;

    // 序列号位数
    private static final int COUNT_TIMESTAMP = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix) {
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        //生成序列号
        String today = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("irc:" + keyPrefix + ":" + today);

        //拼接
        return timeStamp << COUNT_TIMESTAMP | count;
    }


    // 获得当前时间戳
//    public static void main(String[] args) {
//        LocalDateTime now = LocalDateTime.now();
//        long second = now.toEpochSecond(ZoneOffset.UTC);
//        System.out.println(second);
//    }
}
