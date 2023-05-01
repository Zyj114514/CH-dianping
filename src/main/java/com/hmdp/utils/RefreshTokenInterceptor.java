package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 获取token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            // 未登录直接放行到LoginInterceptor处理
            return true;
        }

        //基于token获取redis中用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        if (userMap.isEmpty()) {
            return true;
        }

        //将查询到的hash数据转为UserDTO对象
        UserDTO userDTO = new UserDTO();
        BeanUtil.fillBeanWithMap(userMap,userDTO,false);

        //保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);
        //更新token的有效时间，只要用户还在访问我们就需要更新token的存活时间
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);

        //8.放行
        return true;
    }

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //销毁，以免内存泄漏
        UserHolder.removeUser();
    }
}
