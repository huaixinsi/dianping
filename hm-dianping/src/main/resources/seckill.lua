local voucherId = ARGV[1]
local userId = ARGV[2]
--库存 key--
local stockKey = 'seckill:stock:' .. voucherId
--订单 key--
local orderKey = 'seckill:order:' .. voucherId

--1.判断库存是否充足--

if tonumber(redis.call('get', stockKey)) <= 0 then
    return 1
end
--2.判断用户是否已经下单--
if (redis.call('sismember', orderKey, userId) == 1)
then
    --用户已经下单--
    return 2
end
--3.扣库存--
redis.call('incrby', stockKey, -1)
--4.下单--
redis.call('sadd', orderKey, userId)
--5.返回成功--
return 0
