package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //将任意Java对象序列化为JSON字符串并存储在Redis中
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //逻辑过期对象
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //通过工具类解决缓存穿透问题,并按指定的key查询缓存，反序列化为指定类型返回
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                          Long time, TimeUnit unit) {
        // 1.定义redis中的key
        String key = keyPrefix + id;
        // 2.从redis中查询缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        // 3.判断是否存在，存在直接返回
        if (StrUtil.isNotBlank(Json)) {
            return JSONUtil.toBean(Json, type);
        }
        //因为上一步已经判断为是否是null，所以如果Json不为null，说明是空值（缓存穿透），直接返回错误信息
        if (Json != null) {
            return null;
        }
        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 5.判断数据库中是否存在，不存在将商铺信息返回错误信息
        if (r == null) {
            //将空值写入redis中，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", 1, TimeUnit.MINUTES);
            return null;
        }
        // 6.数据库中存在，存入商铺信息到redis中
        this.set(key, r, time, unit);
        return r;
    }

    //通过工具类解决缓存击穿问题，并按指定的key查询缓存，反序列化为指定类型返回
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                    Long time, TimeUnit unit) {
        // 1.定义redis中的key
        String key = keyPrefix + id;
        // 2.从redis中查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        // 3.判断是否存在，存在直接返回
        if (StrUtil.isBlank(Json)) {
            return null;
        }
        //
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 4.判断是否过期，未过期直接返回店铺信息
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
            // 5.过期则需要缓存重建
            String lockKey = "lock:shop:" + id;
            // 6.获取互斥锁
            boolean isLock = tryLock(lockKey);
            // 7.判断是否获取成功，失败则休眠并重试
            if (isLock) {
                try {
                    R r1 = dbFallback.apply(id);
                    // 8.成功则根据id查询数据库
                    this.setWithLogicalExpire(key, r1, time, unit);
                } finally {
                    // 9.释放互斥锁
                    unLock(lockKey);
                }
            }
        // 10.返回过期的商铺信息
        return r;
    }


    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}

