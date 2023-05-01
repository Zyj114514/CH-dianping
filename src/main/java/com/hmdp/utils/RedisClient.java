package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class RedisClient {

    private final StringRedisTemplate stringRedisTemplate;

    public RedisClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    // 存储对象并设置逻辑过期时间
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        set(key, JSONUtil.toJsonStr(redisData), time, timeUnit);
    }

    /**
     * 解决缓存穿透
     **/
    public <R, Id> R queryWithPassThrough(
            String keyPrefix, Id id, Class<R> type, Function<Id, R> dbFallBack, Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        //1 查询缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        //2 判断存在
        if (StrUtil.isNotBlank(Json)) {
            //3 存在返回
            return JSONUtil.toBean(Json, type);
        }
        if (Json != null) {
            return null;
        }
        //4 不存在查库 需要用户传递函数逻辑
        R r = dbFallBack.apply(id);

        //5 不存在返回错误
        if (r == null) {
            //将空值写入redis 防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //6 存在返回数据
            return null;
        }

        //7 将数据写入redis
        this.set(key, r, time, unit);

        return r;
    }

    //这里需要声明一个线程池，因为下面我们需要新建一个现成来完成重构缓存
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    /**
     * 逻辑过期实现解决缓存击穿
     **/
    public <R, Id> R queryLogicalExpire(
            String keyPrefix, Id id, Class<R> type, Function<Id, R> dbFallBack, Long time, TimeUnit unit) {

        String key = keyPrefix + id;

        //1 查询缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        //2 判断不存在
        if (StrUtil.isBlank(Json)) {
            //3 返回
            return null;
        }
        //4 命中，将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5 判断缓存是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期返回店铺信息
            return r;
        }

        //5.2 已过期，进行缓存重建
        //6 缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        //6.1 获取互斥锁
        boolean flag = tryLock(lockKey);
        //6.2 是否获取成功
        if (flag) {
            //6.3 成功，开启独立线程，实现缓存重建
            //8. 开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    R r1 = dbFallBack.apply(id);
                    this.setWithLogicExpire(key, r1, time, unit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(LOCK_SHOP_KEY + id);
                }
            });
        }
        //6.4 返回过期店铺信息
        return r;
    }

    // 获取锁
    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    // 释放锁
    public void unLock(String key) {
        Boolean flag = stringRedisTemplate.delete(key);
    }


}
