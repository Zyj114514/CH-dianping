package com.hmdp.config;

import com.hmdp.service.IBlogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.Resource;


//@Component
@Slf4j
public class IncSyncPostToEs {

    @Resource
    private IBlogService blogService;

    /**
     * 每分钟执行一次
     */
    @Scheduled(fixedRate = 60 * 1000)
    public void run() {
//        Long id = UserHolder.getUser().getId();
//        if(id ==null) return;
//        blogService.queryHotBlog(id.intValue());
    }
}
