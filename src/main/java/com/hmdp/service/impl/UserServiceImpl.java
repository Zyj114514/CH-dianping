package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 发送验证码功能
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //验证手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //错误返回
            return Result.fail("手机号格式错误");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(4);

        //保存验证码到session
        //session.setAttribute("code", code);

        //保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //System.out.println(stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone));
        //发送验证码
        log.debug("验证码:{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号
        String phone = loginForm.getPhone();
        //验证手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //错误返回
            return Result.fail("手机号格式错误");
        }

        // 校验验证码 session
        // Object code = session.getAttribute("code");

        // 获取验证码 redis
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String cacheCode = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            // 不一致报错
            return Result.fail("验证码错误");
        }

        // 查询用户信息
        User user = query().eq("phone", phone).one();

        // 不存在创建新用户
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        // 存入session
        //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        // 将用户存入redis
        // 随机生成token 登陆令牌
        String token = UUID.randomUUID().toString(true);
        // 将user转为map存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MILLISECONDS);

        //将token传给前端
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 保存
        save(user);
        return user;
    }
}
