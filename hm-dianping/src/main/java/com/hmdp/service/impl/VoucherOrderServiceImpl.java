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
 *  服务实现类
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
    private static final  DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
       SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
       SECKILL_SCRIPT.setResultType(Long.class);
    }
    // 阻塞队列，没有下单就不会有订单信息，消费者线程一直在等待
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //在构造器执行完毕后，执行这个方法
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    //创建一个内部类，专门处理订单
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    //获取订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            //获取锁对象
                    //*RedisLock redisLock = new RedisLock("lock:order:" + userId, stringRedisTemplate);*//*
            Long userId = voucherOrder.getUserId();
            //创建锁对象
            RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        //*boolean isLock = redisLock.tryLock(1200);*//*
            //尝试获取锁
            boolean isLock = redisLock.tryLock();
            if (!isLock) {
                log.error("不允许重复下单");
                return;
            }
            try {
                proxy.getResult(voucherOrder);
            } finally {
                redisLock.unlock();
            }
        }
    }
    private IVoucherOrderService proxy;
    @Transactional
    public Result seckillVoucher(Long voucherId){
        log.info("秒杀优惠券id:{}",voucherId);
        //基于lua脚本实现秒杀
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT
                , Collections.emptyList()
                ,voucherId.toString()  ,userId.toString()
        );
        //2.结果并判断结果
        int r = result.intValue();
        if(r != 0){
            return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
        }

        //3.为0，获取订单id，把下单信息保存在阻塞队列中
        long orderId = redisIdWorker.nextId("order");
        //保存下单信息
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        //获取代理对象
         proxy = (IVoucherOrderService) AopContext.currentProxy();
        //保存到阻塞队列中
        orderTasks.add(voucherOrder);
        //4.返回订单id
        return Result.ok(orderId);


    }


/*    public Result seckillVoucher(Long voucherId){
        // 1.查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        log.info("查询到的优惠券信息:{}",voucher);
        // 2.判断是否存在
        if(voucher == null){
            return Result.fail("优惠券不存在");
        }
        // 3.判断是否开始
        LocalDateTime beginTime = voucher.getBeginTime();
        if(beginTime.isAfter(LocalDateTime.now())){
            return Result.fail("优惠券未开始");
        }
        // 4.判断是否结束
        LocalDateTime endTime = voucher.getEndTime();
        if(endTime.isBefore(LocalDateTime.now())){
            return Result.fail("优惠券已结束");
        }
        // 5.判断库存是否充足
        if(voucher.getStock() < 1){
            return Result.fail("优惠券库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        //获取锁对象
        *//*RedisLock redisLock = new RedisLock("lock:order:" + userId, stringRedisTemplate);*//*
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        *//*boolean isLock = redisLock.tryLock(1200);*//*
        //尝试获取锁
        boolean isLock = redisLock.tryLock();
        if (!isLock) {
            return Result.fail("不允许重复下单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.getResult(voucherId);
        } finally {
            redisLock.unlock();
        }

    }*/

    @Transactional
    public void getResult(VoucherOrder voucherOrder) {
        //5.5实现一人一单
        Long userId = voucherOrder.getUserId();
         Long voucherId = voucherOrder.getVoucherId();
/*            int exists = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (exists > 0) {
                return Result.fail("用户已经购买过一次了");
            }*/
            // 6.扣减库存
            boolean success = seckillVoucherService.update().setSql("stock = stock - 1").
                    eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).
                    update();

        if (!success) {
                log.error("优惠券库存不足");            }
/*            // 7.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setVoucherId(voucherId);
            voucherOrder.setId(redisIdWorker.nextId("order"));
            voucherOrder.setUserId(UserHolder.getUser().getId());*/
            save(voucherOrder);

/*            // 8.返回订单id
            return Result.ok(voucherOrder.getId());*/


    }

}
