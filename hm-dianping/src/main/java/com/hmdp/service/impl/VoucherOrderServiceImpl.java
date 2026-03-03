package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.RedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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
    @Transactional
    public Result seckillVoucher(Long voucherId){
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
        /*RedisLock redisLock = new RedisLock("lock:order:" + userId, stringRedisTemplate);*/
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        /*boolean isLock = redisLock.tryLock(1200);*/
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

    }

    @Transactional
    public Result getResult(Long voucherId) {
        //5.5实现一人一单
        Long userId = UserHolder.getUser().getId();

            int exists = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (exists > 0) {
                return Result.fail("用户已经购买过一次了");
            }
            // 6.扣减库存
            boolean success = seckillVoucherService.update().setSql("stock = stock - 1").
                    eq("voucher_id", voucherId).gt("stock", 0).
                    update();
            if (!success) {
                return Result.fail("优惠券库存不足");
            }
            // 7.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setVoucherId(voucherId);
            voucherOrder.setId(redisIdWorker.nextId("order"));
            voucherOrder.setUserId(UserHolder.getUser().getId());
            save(voucherOrder);

            // 8.返回订单id
            return Result.ok(voucherOrder.getId());


    }

}
