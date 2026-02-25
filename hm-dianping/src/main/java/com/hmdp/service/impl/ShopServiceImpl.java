package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.reactive.TransactionalOperator;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    public Result queryById(Long id){
        //解决缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //解决缓存穿透（工具类）
       /* Shop shop = cacheClient.queryWithPassThrough("cache:shop:", id, Shop.class, this::getById, 30L, TimeUnit.MINUTES);
         if(shop==null){
             return Result.fail("商铺不存在");
         }
         return Result.ok(shop);*/
        //通过工具类解决缓存击穿问题
        Shop shop = cacheClient.queryWithLogicalExpire("cache:shop:", id, Shop.class, this::getById, 30L, TimeUnit.MINUTES);
        if(shop==null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
 /*       // 1.定义redis中的key
        String key = "cache:shop:" + id;
        // 2.从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 3.判断是否存在，存在直接返回
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //因为上一步已经判断为是否是null，所以如果shopJson不为null，说明是空值（缓存穿透），直接返回错误信息
        if(shopJson != null){
            return Result.fail("商铺不存在");
        }
        // 4.不存在，根据id查询数据库
        Shop shop =getById(id);
        // 5.判断数据库中是否存在，不存在将商铺信息返回错误信息
        if(shop==null){
            //将空值写入redis中，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", 1, TimeUnit.MINUTES);
            return Result.fail("商铺不存在");
        }
        // 6.数据库中存在，存入商铺信息到redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
        return Result.ok(shop);*/
    }
/*    public Shop queryWithMutex(Long id){//解决缓存击穿
        // 1.定义redis中的key
        String key = "cache:shop:" + id;
        // 2.从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 3.判断是否存在，存在直接返回
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        String lockKey = null;
        Shop shop = null;
        try {
            //缓存重建
            // 4.获取互斥锁
            lockKey = "lock:shop:" + id;
            boolean isLock = tryLock(lockKey);
            // 5.判断是否获取成功，失败则休眠并重试
            if(!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 6.成功则根据id查询数据库
            shop = getById(id);
            if(shop==null){
                //将空值写入redis中，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", 1, TimeUnit.MINUTES);
                return null;
            }
            // 7.将商铺信息存入redis中
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
            // 8.释放互斥锁
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            unLock(lockKey);
        }

        return shop;

    }
    public Shop queryWithPassThrough(Long id)//防止缓存穿透
    {
        // 1.定义redis中的key
        String key = "cache:shop:" + id;
        // 2.从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 3.判断是否存在，存在直接返回
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //因为上一步已经判断为是否是null，所以如果shopJson不为null，说明是空值（缓存穿透），直接返回错误信息
        if(shopJson != null){
            return null;
        }
        // 4.不存在，根据id查询数据库
        Shop shop =getById(id);
        // 5.判断数据库中是否存在，不存在将商铺信息返回错误信息
        if(shop==null){
            //将空值写入redis中，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", 1, TimeUnit.MINUTES);
            return null;
        }
        // 6.数据库中存在，存入商铺信息到redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
        return shop;
    }*/
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop){
        long key = shop.getId();
        if(key==0){
            return Result.fail("商铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete("cache:shop:" + shop.getId());
        return Result.ok();
    }
}
