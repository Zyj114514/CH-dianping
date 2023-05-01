package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IBlogService blogService;
    @Resource
    private IFollowService followService;


    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            // 查询是否点赞
            this.isBlogLike(blog);
            this.queryBlog(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {

        Blog byId = getById(id);
        if (byId == null) {
            return Result.fail("笔记不存在...");
        }
        queryBlog(byId);
        // 查询是否点赞
        isBlogLike(byId);
        return Result.ok(byId);
    }

    private void isBlogLike(Blog blog) {
        // 获取当前用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        String key = BLOG_LIKED_KEY + blog.getId();
        // 判断是否点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        blog.setIsLike(score != null);
    }


    @Override
    public Result likeBlog(Long id) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        // 判断是否点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // 未点赞
        if (score == null) {
            // 数据库+1
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            // 存redis
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 点过赞 删除点赞
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            // 数据库-1
            // 从redis移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    // 查询点赞列表
    public Result queryBlogLikes(Integer id) {

        String key = BLOG_LIKED_KEY + id;
        // 查询前五个元素
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        // 如果是空的(可能没人点赞)，直接返回一个空集合
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 将ids使用`,`拼接，SQL语句查询出来的结果并不是按照我们期望的方式进行排
        // 所以我们需要用order by field来指定排序方式，期望的排序方式就是按照查询出来的id进行排序
        String idsStr = StrUtil.join(",", ids);
        // select * from tb_user where id in (ids[0], ids[1] ...) order by field(id, ids[0], ids[1] ...)
        List<UserDTO> userDTOS = userService.query().in("id", ids)
                .last("order by field(id," + idsStr + ")")
                .list().stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);

    }

    // 推送博客
    @Override
    public Result saveBlog(Blog blog) {

        // 获取用户信息
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        // 保存探店笔记
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败...");
        }
        // 查询粉丝信息
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 推送给所有粉丝
        for (Follow follow : follows) {
            // 粉丝id
            Long id = follow.getId();
            //4.2 推送
            String key= RedisConstants.FEED_KEY+userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(),System.currentTimeMillis());
        }

        return Result.ok(blog.getId());
    }

    private void queryBlog(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
