# 黑码点评系统 - Redis实战项目解析

> 本文将深入解析一个基于Spring Boot + Redis的O2O点评平台，重点讲解Redis在实际业务场景中的应用，包括缓存策略、分布式锁、秒杀系统等核心技术。

## 项目概述

黑码点评是一个功能完整的本地生活服务平台，类似于大众点评，涵盖用户管理、商铺浏览、优惠券领取、秒杀抢购、探店笔记等核心功能。项目最大的特色是**全面应用Redis解决高并发场景下的性能问题**。

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 2.3.12 | 核心框架 |
| MyBatis-Plus | 3.4.3 | ORM框架 |
| Redis | - | 缓存、分布式锁、消息队列 |
| Redisson | 3.13.6 | 分布式锁 |
| RabbitMQ | - | 异步消息处理 |
| MySQL | 8.0.33 | 数据持久化 |
| Druid | 1.2.20 | 连接池 |

---

## 一、用户登录模块

### 1.1 业务流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  输入手机号  │────▶│  发送验证码  │────▶│  Redis存储  │
└─────────────┘     └─────────────┘     └─────────────┘
                                               │
                                               ▼
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  登录成功   │◀────│  校验验证码  │◀────│  用户输入   │
└─────────────┘     └─────────────┘     └─────────────┘
       │
       ▼
┌─────────────┐     ┌─────────────┐
│  生成Token  │────▶│  Redis存储  │
└─────────────┘     └─────────────┘
```

### 1.2 核心代码实现

**验证码发送流程：**

```java
public Result sendCode(String phone, HttpSession session) {
    // 1. 校验手机号格式
    if (!phone.matches("^1[3-9]\\d{9}$")) {
        return Result.fail("手机号格式不正确");
    }
    
    // 2. 生成6位随机验证码
    String code = RandomUtil.randomNumbers(6);
    
    // 3. 存入Redis，设置5分钟过期
    stringRedisTemplate.opsForValue().set(
        "login:code:" + phone, 
        code, 
        5, 
        TimeUnit.MINUTES
    );
    
    // 4. 发送验证码（实际项目中调用短信服务）
    log.debug("发送短信验证码成功，验证码：{}", code);
    return Result.ok();
}
```

**登录流程：**

```java
public Result login(LoginFormDTO loginForm, HttpSession session) {
    // 1. 从Redis获取验证码
    String code = stringRedisTemplate.opsForValue()
        .get("login:code:" + loginForm.getPhone());
    
    // 2. 校验验证码
    if (code == null || !code.equals(loginForm.getCode())) {
        return Result.fail("验证码不正确");
    }
    
    // 3. 查询或创建用户
    User user = query().eq("phone", loginForm.getPhone()).one();
    if (user == null) {
        user = createUserWithPhone(loginForm.getPhone());
    }
    
    // 4. 生成Token
    String token = "login:token" + UUID.randomUUID().toString();
    
    // 5. 用户信息存入Redis Hash，设置30分钟过期
    UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
    Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>(),
        CopyOptions.create()
            .setIgnoreNullValue(true)
            .setFieldValueEditor((fieldName, fieldValue) -> 
                fieldValue == null ? null : fieldValue.toString()));
    
    stringRedisTemplate.opsForHash().putAll(token, map);
    stringRedisTemplate.expire(token, 30, TimeUnit.MINUTES);
    
    return Result.ok(token);
}
```

### 1.3 Token刷新机制

使用**双拦截器**实现Token自动刷新：

```java
// RefreshTokenInterceptor - 刷新Token有效期
public boolean preHandle(HttpServletRequest request, 
                         HttpServletResponse response, 
                         Object handler) {
    // 1. 获取请求头中的Token
    String token = request.getHeader("authorization");
    if (token == null || token.trim().isEmpty()) {
        return true; // 放行，让LoginInterceptor处理
    }
    
    // 2. 从Redis获取用户信息
    Map<Object, Object> user = stringRedisTemplate
        .opsForHash().entries(token);
    
    if (user.isEmpty()) {
        return true;
    }
    
    // 3. 保存用户到ThreadLocal
    UserDTO userDTO = BeanUtil.fillBeanWithMap(user, new UserDTO(), false);
    UserHolder.saveUser(userDTO);
    
    // 4. 刷新Token有效期（关键！）
    stringRedisTemplate.expire(token, 30, TimeUnit.MINUTES);
    
    return true;
}
```

**设计亮点：**
- ✅ 使用Redis Hash存储用户信息，便于字段扩展
- ✅ Token自动刷新机制，用户活跃期间永不过期
- ✅ ThreadLocal存储当前登录用户，线程安全

---

## 二、商铺缓存模块

### 2.1 商铺查询业务流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  用户请求    │────▶│  查询缓存    │────▶│  命中缓存？  │
└─────────────┘     └─────────────┘     └─────────────┘
                                               │
                    ┌──────────────────────────┼──────────────────────────┐
                    │                          │                          │
                    ▼                          ▼                          ▼
              ┌──────────┐              ┌──────────┐              ┌──────────┐
              │  返回数据  │              │ 空值缓存？│              │ 查询数据库 │
              └──────────┘              └──────────┘              └──────────┘
                    │                          │                          │
                    │                    ┌─────┴─────┐                  │
                    │                    ▼           ▼                  │
                    │              ┌──────────┐ ┌──────────┐           │
                    │              │ 返回null  │ │ 写入缓存  │           │
                    │              └──────────┘ └──────────┘           │
                    │                    │                          │
                    └────────────────────┼──────────────────────────┘
                                         ▼
                                  ┌──────────┐
                                  │  返回数据  │
                                  └──────────┘
```

### 2.2 商铺更新业务流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  用户请求    │────▶│  更新数据库  │────▶│  删除缓存    │
└─────────────┘     └─────────────┘     └─────────────┘
       │
       ▼
┌─────────────┐
│  返回成功    │
└─────────────┘
```

### 2.3 缓存穿透解决方案

**问题描述：** 查询一个不存在的数据，导致每次都要访问数据库。

**解决方案：** 缓存空值

```java
public <R, ID> R queryWithPassThrough(
        String keyPrefix, ID id, Class<R> type, 
        Function<ID, R> dbFallback, Long time, TimeUnit unit) {
    
    String key = keyPrefix + id;
    
    // 1. 从Redis查询
    String json = stringRedisTemplate.opsForValue().get(key);
    
    // 2. 存在且不为空，直接返回
    if (StrUtil.isNotBlank(json)) {
        return JSONUtil.toBean(json, type);
    }
    
    // 3. 存在但为空字符串（空值缓存），返回null
    if (json != null) {
        return null;
    }
    
    // 4. 不存在，查询数据库
    R r = dbFallback.apply(id);
    
    // 5. 数据库也不存在，缓存空值（防穿透）
    if (r == null) {
        stringRedisTemplate.opsForValue()
            .set(key, "", 1, TimeUnit.MINUTES);
        return null;
    }
    
    // 6. 存在，写入Redis
    this.set(key, r, time, unit);
    return r;
}
```

### 2.2 缓存击穿解决方案

**问题描述：** 热点key突然失效，大量请求同时访问数据库。

**方案一：互斥锁**

```java
public Shop queryWithMutex(Long id) {
    String key = "cache:shop:" + id;
    String lockKey = "lock:shop:" + id;
    
    // 1. 查询缓存
    String shopJson = stringRedisTemplate.opsForValue().get(key);
    if (StrUtil.isNotBlank(shopJson)) {
        return JSONUtil.toBean(shopJson, Shop.class);
    }
    
    Shop shop = null;
    try {
        // 2. 获取互斥锁
        boolean isLock = tryLock(lockKey);
        if (!isLock) {
            // 3. 获取失败，休眠重试
            Thread.sleep(50);
            return queryWithMutex(id); // 递归重试
        }
        
        // 4. 获取成功，再次检查缓存（Double Check）
        shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        
        // 5. 查询数据库
        shop = getById(id);
        
        // 6. 写入缓存
        if (shop == null) {
            stringRedisTemplate.opsForValue()
                .set(key, "", 1, TimeUnit.MINUTES);
        } else {
            stringRedisTemplate.opsForValue()
                .set(key, JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
        }
    } finally {
        // 7. 释放锁
        unLock(lockKey);
    }
    
    return shop;
}
```

**方案二：逻辑过期（推荐）**

```java
public <R, ID> R queryWithLogicalExpire(
        String keyPrefix, ID id, Class<R> type,
        Function<ID, R> dbFallback, Long time, TimeUnit unit) {
    
    String key = keyPrefix + id;
    
    // 1. 查询Redis
    String json = stringRedisTemplate.opsForValue().get(key);
    if (StrUtil.isBlank(json)) {
        return null; // 未命中，说明不是热点数据
    }
    
    // 2. 反序列化
    RedisData redisData = JSONUtil.toBean(json, RedisData.class);
    R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
    LocalDateTime expireTime = redisData.getExpireTime();
    
    // 3. 判断是否过期
    if (expireTime.isAfter(LocalDateTime.now())) {
        return r; // 未过期，直接返回
    }
    
    // 4. 已过期，需要重建
    String lockKey = "lock:shop:" + id;
    boolean isLock = tryLock(lockKey);
    
    if (isLock) {
        // 5. 获取锁成功，开启独立线程重建
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                R r1 = dbFallback.apply(id);
                this.setWithLogicalExpire(key, r1, time, unit);
            } finally {
                unLock(lockKey);
            }
        });
    }
    
    // 6. 返回过期数据（保证可用性）
    return r;
}
```

**逻辑过期数据结构：**

```java
@Data
public class RedisData {
    private LocalDateTime expireTime;  // 逻辑过期时间
    private Object data;               // 实际数据
}
```

### 2.3 缓存雪崩解决方案

**问题描述：** 大量key同时过期，导致数据库压力激增。

**解决方案：**

1. **随机TTL**：给缓存过期时间添加随机值
```java
// 基础过期时间 + 随机值（0-10分钟）
long expireTime = 30 + RandomUtil.randomInt(10);
stringRedisTemplate.opsForValue()
    .set(key, value, expireTime, TimeUnit.MINUTES);
```

2. **多级缓存**：本地缓存(Caffeine) + Redis + 数据库

3. **熔断降级**：使用Sentinel或Hystrix限流

---

## 三、秒杀系统模块

### 3.1 秒杀业务流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  用户请求    │────▶│  校验登录    │────▶│  执行Lua脚本 │
└─────────────┘     └─────────────┘     └─────────────┘
                                               │
                    ┌──────────────────────────┼──────────────────────────┐
                    │                          │                          │
                    ▼                          ▼                          ▼
              ┌──────────┐              ┌──────────┐              ┌──────────┐
              │ 库存不足  │              │ 重复下单  │              │  购买成功  │
              └──────────┘              └──────────┘              └──────────┘
                    │                          │                          │
                    ▼                          ▼                          ▼
              ┌──────────┐              ┌──────────┐              ┌──────────┐
              │ 返回失败  │              │ 返回失败  │              │ 创建订单  │
              └──────────┘              └──────────┘              └──────────┘
                                                                          │
                                                                          ▼
                                                                   ┌──────────┐
                                                                   │ 发送MQ    │
                                                                   └──────────┘
                                                                          │
                                                                          ▼
                                                                   ┌──────────┐
                                                                   │ 返回订单号│
                                                                   └──────────┘
```

### 3.2 秒杀订单异步处理流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  MQ监听      │────▶│  获取分布式锁│────▶│  创建订单    │
└─────────────┘     └─────────────┘     └─────────────┘
       │
       ▼
┌─────────────┐
│  释放锁      │
└─────────────┘
       │
       ▼
┌─────────────┐
│  处理完成    │
└─────────────┘
```

### 3.3 秒杀优惠券发布流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  管理员请求  │────▶│  保存优惠券  │────▶│  保存秒杀信息│
└─────────────┘     └─────────────┘     └─────────────┘
       │
       ▼
┌─────────────┐
│  写入Redis   │
│  (库存信息)  │
└─────────────┘
       │
       ▼
┌─────────────┐
│  返回成功    │
└─────────────┘
```

### 3.4 核心问题分析

| 问题 | 解决方案 | 实现方式 |
|------|----------|----------|
| 超卖 | 原子性扣减 | Redis + Lua脚本 |
| 重复下单 | 唯一性判断 | Redis Set |
| 高并发压力 | 异步处理 | RabbitMQ |
| 订单ID生成 | 全局唯一ID | Redis自增 + 时间戳 |

### 3.3 Lua脚本实现原子性操作

```lua
-- seckill.lua
local voucherId = ARGV[1]
local userId = ARGV[2]

-- 定义key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 1. 判断库存
if tonumber(redis.call('get', stockKey)) <= 0 then
    return 1  -- 库存不足
end

-- 2. 判断是否重复下单
if redis.call('sismember', orderKey, userId) == 1 then
    return 2  -- 重复下单
end

-- 3. 扣减库存
redis.call('incrby', stockKey, -1)

-- 4. 记录下单用户
redis.call('sadd', orderKey, userId)

return 0  -- 成功
```

### 3.4 Java代码实现

```java
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> 
    implements IVoucherOrderService {
    
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;
    
    // Lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        
        // 1. 执行Lua脚本，判断购买资格
        Long result = stringRedisTemplate.execute(
            SECKILL_SCRIPT,
            Collections.emptyList(),
            voucherId.toString(), 
            userId.toString()
        );
        
        // 2. 判断结果
        int r = result.intValue();
        if (r == 1) {
            return Result.fail("库存不足！");
        }
        if (r == 2) {
            return Result.fail("不能重复下单！");
        }
        
        // 3. 有资格，创建订单信息
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        
        // 4. 发送到消息队列，异步处理
        rabbitTemplate.convertAndSend("seckill.exchange", 
                                      "seckill.routing.key", 
                                      voucherOrder);
        
        // 5. 立即返回订单号
        return Result.ok(orderId);
    }
}
```

### 3.5 全局唯一ID生成器

使用Redis自增 + 时间戳生成64位唯一ID：

```java
@Component
public class RedisIdWorker {
    
    // 开始时间戳（2022-01-01）
    public static final long BEGIN_TIMESTAMP = 1640995200L;
    
    public long nextId(String keyPrefix) {
        // 1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        
        // 2. 生成序列号（Redis自增）
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue()
            .increment("icr:" + keyPrefix + ":" + date);
        
        // 3. 拼接：时间戳(32位) + 序列号(32位)
        return timestamp << 32 | count;
    }
}

// ID结构：
// ┌─────────────────────────────┬─────────────────────────────┐
// │      时间戳(32位)            │       序列号(32位)           │
// └─────────────────────────────┴─────────────────────────────┘
```

### 3.6 订单异步处理

```java
@Component
@Slf4j
public class SeckillConsumer {
    
    @RabbitListener(queues = "seckill.queue")
    public void receive(VoucherOrder voucherOrder) {
        log.info("接收到秒杀订单：{}", voucherOrder);
        
        Long userId = voucherOrder.getUserId();
        
        // 1. 获取分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        
        try {
            boolean isLock = lock.tryLock();
            if (!isLock) {
                log.error("获取锁失败");
                return;
            }
            
            // 2. 创建订单（数据库操作）
            createVoucherOrder(voucherOrder);
            
        } finally {
            lock.unlock();
        }
    }
}
```

---

## 四、分布式锁实现

### 4.1 分布式锁获取流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  请求获取锁  │────▶│  尝试SET NX │────▶│  获取成功？  │
└─────────────┘     └─────────────┘     └─────────────┘
                                               │
                    ┌──────────────────────────┼──────────────────────────┐
                    │                          │                          │
                    ▼                          ▼                          ▼
              ┌──────────┐              ┌──────────┐              ┌──────────┐
              │  执行业务  │              │  休眠重试  │              │  返回失败  │
              └──────────┘              └──────────┘              └──────────┘
                    │                          │
                    ▼                          ▼
              ┌──────────┐              ┌──────────┐
              │  释放锁    │              │  重试获取  │
              └──────────┘              └──────────┘
```

### 4.2 分布式锁释放流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  请求释放锁  │────▶│  执行Lua脚本│────▶│  判断锁归属  │
└─────────────┘     └─────────────┘     └─────────────┘
                                               │
                    ┌──────────────────────────┼──────────────────────────┐
                    │                          │                          │
                    ▼                          ▼                          ▼
              ┌──────────┐              ┌──────────┐              ┌──────────┐
              │  删除锁    │              │  不处理    │              │  返回失败  │
              └──────────┘              └──────────┘              └──────────┘
```

### 4.3 基于Redis的分布式锁

```java
public class RedisLock implements ILock {
    
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    
    // 锁前缀
    private static final String KEY_PREFIX = "lock:";
    // UUID作为线程标识
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        
        // 获取锁（SET key value NX EX seconds）
        Boolean success = stringRedisTemplate.opsForValue()
            .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        
        return Boolean.TRUE.equals(success);
    }
    
    @Override
    public void unlock() {
        // 使用Lua脚本保证原子性
        String script = 
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";
        
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        stringRedisTemplate.execute(
            new DefaultRedisScript<>(script, Long.class),
            Collections.singletonList(KEY_PREFIX + name),
            threadId
        );
    }
}
```

### 4.2 Redisson分布式锁（推荐）

```java
@Resource
private RedissonClient redissonClient;

public void doSomething(Long userId) {
    // 获取锁
    RLock lock = redissonClient.getLock("lock:order:" + userId);
    
    try {
        // 尝试获取锁，最多等待10秒，锁30秒后自动释放
        boolean isLock = lock.tryLock(10, 30, TimeUnit.SECONDS);
        if (!isLock) {
            log.error("获取锁失败");
            return;
        }
        
        // 执行业务逻辑
        // ...
        
    } catch (InterruptedException e) {
        throw new RuntimeException(e);
    } finally {
        // 释放锁
        lock.unlock();
    }
}
```

**Redisson优势：**
- ✅ 可重入锁
- ✅ 自动续期（Watch Dog机制）
- ✅ 阻塞等待
- ✅ 公平锁/非公平锁

---

## 五、博客/探店模块

### 5.1 发布探店笔记流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  用户请求    │────▶│  校验登录    │────▶│  保存笔记    │
└─────────────┘     └─────────────┘     └─────────────┘
       │
       ▼
┌─────────────┐
│  返回笔记ID  │
└─────────────┘
```

### 5.2 点赞业务流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  用户点赞    │────▶│  校验登录    │────▶│  判断是否点赞│
└─────────────┘     └─────────────┘     └─────────────┘
                                               │
                    ┌──────────────────────────┼──────────────────────────┐
                    │                          │                          │
                    ▼                          ▼                          ▼
              ┌──────────┐              ┌──────────┐              ┌──────────┐
              │  已点赞   │              │  未点赞   │              │  取消点赞  │
              └──────────┘              └──────────┘              └──────────┘
                    │                          │                          │
                    ▼                          ▼                          ▼
              ┌──────────┐              ┌──────────┐              ┌──────────┐
              │  减少点赞  │              │  增加点赞  │              │  减少点赞  │
              │  Redis Set│              │  Redis Set│              │  Redis Set│
              └──────────┘              └──────────┘              └──────────┘
                    │                          │                          │
                    └──────────────────────────┼──────────────────────────┘
                                         ▼
                                  ┌──────────┐
                                  │  更新数据库 │
                                  └──────────┘
                                         │
                                         ▼
                                  ┌──────────┐
                                  │  返回成功  │
                                  └──────────┘
```

### 5.3 查询热门笔记流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  用户请求    │────▶│  分页查询    │────▶│  按点赞排序  │
└─────────────┘     └─────────────┘     └─────────────┘
       │
       ▼
┌─────────────┐
│  查询用户信息 │
└─────────────┘
       │
       ▼
┌─────────────┐
│  组装返回数据 │
└─────────────┘
       │
       ▼
┌─────────────┐
│  返回笔记列表 │
└─────────────┘
```

---

## 六、关注模块

### 6.1 关注用户流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  用户请求    │────▶│  校验登录    │────▶│  判断是否关注│
└─────────────┘     └─────────────┘     └─────────────┘
                                               │
                    ┌──────────────────────────┼──────────────────────────┐
                    │                          │                          │
                    ▼                          ▼                          ▼
              ┌──────────┐              ┌──────────┐              ┌──────────┐
              │  已关注   │              │  未关注   │              │  取消关注  │
              └──────────┘              └──────────┘              └──────────┘
                    │                          │                          │
                    ▼                          ▼                          ▼
              ┌──────────┐              ┌──────────┐              ┌──────────┐
              │  返回失败  │              │  添加关注  │              │  移除关注  │
              └──────────┘              │  Redis Set│              │  Redis Set│
                                         └──────────┘              └──────────┘
                                               │                          │
                                               ▼                          ▼
                                         ┌──────────┐              ┌──────────┐
                                         │  保存数据库 │              │  删除数据库 │
                                         └──────────┘              └──────────┘
                                               │                          │
                                               └──────────────────────────┘
                                                        │
                                                        ▼
                                                 ┌──────────┐
                                                 │  返回成功  │
                                                 └──────────┘
```

### 6.2 查询关注列表流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  用户请求    │────▶│  校验登录    │────▶│  查询关注列表 │
└─────────────┘     └─────────────┘     └─────────────┘
       │
       ▼
┌─────────────┐
│  查询用户信息 │
└─────────────┘
       │
       ▼
┌─────────────┐
│  组装返回数据 │
└─────────────┘
       │
       ▼
┌─────────────┐
│  返回列表    │
└─────────────┘
```

---

## 七、优惠券模块

### 7.1 查询商铺优惠券流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  用户请求    │────▶│  查询优惠券  │────▶│  过滤有效券  │
└─────────────┘     └─────────────┘     └─────────────┘
       │
       ▼
┌─────────────┐
│  返回优惠券列表│
└─────────────┘
```

### 7.2 普通优惠券发布流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  管理员请求  │────▶│  校验权限    │────▶│  保存优惠券  │
└─────────────┘     └─────────────┘     └─────────────┘
       │
       ▼
┌─────────────┐
│  返回优惠券ID│
└─────────────┘
```

---

## 八、项目总结

### 8.1 系统整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         客户端层                                │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐        │
│  │  Web端   │  │  App端   │  │  小程序  │  │  H5端    │        │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                         网关层                                  │
│                    Nginx / API Gateway                          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      应用层 (Spring Boot)                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐        │
│  │用户模块  │  │商铺模块  │  │秒杀模块  │  │博客模块  │        │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘        │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐        │
│  │关注模块  │  │优惠券模块│  │订单模块  │  │上传模块  │        │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                       服务层                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐        │
│  │业务逻辑  │  │缓存处理  │  │分布式锁  │  │消息队列  │        │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                       数据层                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐        │
│  │  Redis   │  │  MySQL   │  │RabbitMQ  │  │  OSS     │        │
│  │  缓存    │  │  数据库  │  │ 消息队列  │  │ 文件存储  │        │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘        │
└─────────────────────────────────────────────────────────────────┘
```

### 8.2 Redis应用场景总结

| 场景 | 数据类型 | 实现方式 |
|------|----------|----------|
| 会话缓存 | Hash | 用户信息存储 |
| 验证码 | String | 过期时间控制 |
| 商铺缓存 | String | JSON序列化 |
| 分布式锁 | String | SET NX EX |
| 秒杀库存 | String | Lua原子操作 |
| 防重复下单 | Set | 用户ID去重 |
| 全局ID | String | INCR自增 |
| 关注关系 | Set | 交集并集运算 |
| 排行榜 | ZSet | 分数排序 |
| 地理位置 | Geo | 附近商铺 |

### 8.3 性能优化建议

1. **缓存设计原则**
   - 读多写少的数据才适合缓存
   - 设置合理的过期时间
   - 热点数据永不过期 + 逻辑过期

2. **高并发处理**
   - 接口限流（Sentinel）
   - 消息队列削峰填谷
   - 数据库连接池优化

3. **监控与运维**
   - Redis慢查询监控
   - 缓存命中率统计
   - 内存使用监控


