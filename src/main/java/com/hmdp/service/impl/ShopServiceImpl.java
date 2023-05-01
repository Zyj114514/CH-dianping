package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisClient redisClient;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
//        Shop shop = redisClient
//                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

//        互斥锁解决缓存击穿
         Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存穿透
        //redisClient.queryLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) return Result.fail("店铺不存在!");


        return Result.ok(shop);
    }

    // 缓存穿透
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;

        //1 查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2 判断存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3 存在返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson != null) {
            return null;
        }
        //4 不存在查库
        Shop shop = getById(id);
        //5 不存在返回错误
        if (shop == null) {
            //将空值写入redis 防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //6 存在返回数据
            return null;
        }

        //7 将数据写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    //这里需要声明一个线程池，因为下面我们需要新建一个现成来完成重构缓存
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    // 逻辑过期解决缓存击穿
    public Shop queryLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;

        //1 查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2 判断不存在
        if (StrUtil.isBlank(shopJson)) {
            //3 返回
            return null;
        }
        //4 命中，将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5 判断缓存是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期返回店铺信息
            return shop;
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
                    this.saveShop2Redis(id, LOCK_SHOP_TTL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(LOCK_SHOP_KEY + id);
                }
            });
        }
        //6.4 返回过期或未过期店铺信息
        return shop;
    }

    // 互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;

        //1 查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2 判断存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3 存在返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson != null) {
            return null;
        }
        Shop shop = null;
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            boolean flag = tryLock(lockKey);
            if (!flag) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 获取锁成功查库
            shop = getById(id);
            // 模拟重建延时
            Thread.sleep(200);
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //3.数据库数据写入Redis
            //手动序列化
            String shopStr = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, shopStr, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }

    // 设置逻辑过期时间
    public void saveShop2Redis(Long id, Long expireTime) throws InterruptedException {
        // 查询店铺信息
        Shop shop = getById(id);
        // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
                                                    // 设置逻辑过期时间
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        // 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
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


    @Override
    @Transactional
    public Result update(Shop shop) {
        Long shopId = shop.getId();
        if (shopId == null) {
            return Result.fail("店铺Id不能为空...");
        }
        //先更新数据库
        updateById(shop);
        //再删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);

        return Result.ok();
    }
}
