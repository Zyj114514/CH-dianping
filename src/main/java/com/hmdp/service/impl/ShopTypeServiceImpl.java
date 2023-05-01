package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryList() {

        //先从Redis中查，这里的常量值是固定前缀 + 店铺id
        List<String> shopTypes =
                stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, -1);

        //查到返回
        if(!shopTypes.isEmpty()){
            List<ShopType> tmp = new ArrayList<>();
            for (String types : shopTypes) {
                ShopType shopType = JSONUtil.toBean(types, ShopType.class);
                tmp.add(shopType);
            }
            return Result.ok(tmp);
        }

        //否则去数据库中查
        List<ShopType> tmp = query().orderByAsc("sort").list();
        if (tmp == null){
            return Result.fail("店铺类型不存在！！");
        }

        //查到转为json字符串存到redis
        for (ShopType shopType : tmp) {
            String jsonStr = JSONUtil.toJsonStr(shopType);
            shopTypes.add(jsonStr);
        }
        stringRedisTemplate.opsForList().leftPushAll(CACHE_SHOP_TYPE_KEY,shopTypes);

        return Result.ok(tmp);
    }
}
