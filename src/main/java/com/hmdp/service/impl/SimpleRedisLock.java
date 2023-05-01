package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import com.hmdp.service.ILock;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;


public class SimpleRedisLock implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true);
    // 用于执行lua脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    // 初始化UNLOCK_SCRIPT
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unLock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String key = KEY_PREFIX + name;
        //value的话一般设置为哪个线程持有该锁即可
        // 获得当前线程Id
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, threadId, timeoutSec, TimeUnit.SECONDS);

        // 最好不要直接return success，因为我们返回的是boolean类型，
        // 现在得到的是Boolean的结果，就会进行自动装箱，如果success为null,就会出现空指针异常
        return Boolean.TRUE.equals(success);//null的话也是返回false
    }

    @Override
    public void unLock() {

        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );

//        // 获得当前线程Id
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        // 获得当前线程标识
//        String key = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if (threadId.equals(key)) {
//            stringRedisTemplate.delete(key);
//        }
    }
}
