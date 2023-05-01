package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private IVoucherOrderService proxy;

    // 用于执行lua脚本
    private static final DefaultRedisScript<Long> SECKII_SCRIPT;

    // 初始化UNLOCK_SCRIPT
    static {
        SECKII_SCRIPT = new DefaultRedisScript<>();
        SECKII_SCRIPT.setLocation(new ClassPathResource("unLock.lua"));
        SECKII_SCRIPT.setResultType(Long.class);
    }

    // 创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //  创建阻塞队列
    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    @PostConstruct
    private void  init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true) {
                try {
                    // 获取队列中的信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 创建订单
                    handleVoucherOrder(voucherOrder);

                }catch (Exception e){
                    log.error("处理订单异常。。。",e);
                }
            }
        }
    }

    //异步下单
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();//由于多线程，所以不能直接去ThreadLocal取
        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId,stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean hasLock = lock.tryLock( );
        if(!hasLock){
            //获取锁失败
            log.error("不允许重复下单!");
            return;
        }

        try {
            //代理对象改成全局变量
            proxy.createVoucherOrder(voucherOrder);//默认是this,我们要实现事务需要proxy
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }

    }
    @Override
    public Result seckillVoucher(Long voucherId) {

        Long result = stringRedisTemplate.execute(SECKII_SCRIPT,
                Collections.emptyList(), voucherId.toString(),
                UserHolder.getUser().getId().toString());
        if (result.intValue() != 0) {
            return Result.fail(result.intValue() == 1 ? "库存不足" : "不能重复下单");
        }
        long orderId = redisIdWorker.nextId("order");
        //封装到voucherOrder中
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setId(orderId);
        //加入到阻塞队列
        orderTasks.add(voucherOrder);
        //主线程获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //查询订单看看是否存在
        Long userId = UserHolder.getUser().getId();

        if (query().eq("user_id",userId).eq("voucher_id", voucherOrder.getUserId()).count()>0) {
            log.error("用户已经购买过一次!");
            return;
        }

        //4.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock",0)//where id = ? and stock >0 添加了乐观锁
                .update();

        if(!success){
            log.error("优惠券库存不足!");
            return;
        }

        //7.订单写入数据库
        save(voucherOrder);
    }


    // 没用异步操作
    // 没用异步操作
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//
//        // 查询优惠券信息
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 判断是否开始
//        if (voucher.getCreateTime().isAfter(LocalDateTime.now())) {
//            // 未开始返回null
//            return Result.fail("抢购未开始...");
//        }
//        // 判断是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已结束...");
//        }
//        // 开始判断是否充足
//        if (voucher.getStock() < 1) {
//            // 库存不足返回空
//            return Result.fail("库存不足...");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//
///*        //userId一样的持有同一把锁，最好不要放在整个方法上,intern:去字符串常量池找相同字符串
//        synchronized (userId.toString().intern()) {
//            // 获得代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return  proxy.createVoucherOrder(voucherId);
//        }   //先获取锁，然后再进入方法，确保我的前一个订单会添加上,能先提交事务再释放锁
//*/
//
///*
//        // 创建锁对象
//        //SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
//        // 获取锁
//        //boolean hasLock = lock.tryLock(1200);
// */
//
//        //Redisson 创建锁对象
//        RLock lock = redissonClient.getLock("order" + userId);
//        //Redisson 获取锁
//        boolean hasLock = lock.tryLock();
//
//        if (!hasLock) {
//            return Result.fail("请勿重复下单...");
//        }
//
//        try {
//            // 获得代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            // 创建订单
//            return proxy.createVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            lock.unlock();
//        }
//
//    }

    // 没用异步
    /*@Transactional
    public Result createVoucherOrder(VoucherOrder voucherId) {
        // 一人一单
        Long userId = UserHolder.getUser().getId();

        //查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();

        //订单已存在
        if (count > 0) {
            return Result.fail("该用户已有订单...");
        }

        // 扣减库存
        boolean success = seckillVoucherService.update()
                // 乐观锁解决超卖问题
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();

        if (!success) {
            return Result.fail("优惠券库存不足...");
        }

        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();

        // 用户id
        voucherOrder.setUserId(userId);
        // 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 优惠券id
        voucherOrder.setVoucherId(voucherId);

        // 保存订单
        save(voucherOrder);

        // 返回订单id
        return Result.ok(orderId);
    }*/
}
