package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class RedisLock implements ILock{
    private String name; //锁的名字
    private String id; //锁的标识
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString();
    private StringRedisTemplate stringRedisTemplate;
    private static final  DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    public RedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识
        String threadId = ID_PREFIX+Thread.currentThread().getId() ;
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);//防止自动拆箱时出现空指针异常
    }

    @Override
    public void unlock() {
        //调用lua脚本
            stringRedisTemplate.execute(UNLOCK_SCRIPT,
                    Collections.singletonList(KEY_PREFIX + name),
                    ID_PREFIX+Thread.currentThread().getId());
 /*       //获取线程标识
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        //获取锁中的标识
        String value = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断是否相等
        if(threadId.equals(value)){
            //删除锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }*/

    }
}
