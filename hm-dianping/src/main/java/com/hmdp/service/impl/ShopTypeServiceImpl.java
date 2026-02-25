package com.hmdp.service.impl;

import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IShopTypeService typeService;

    @Override
    public Result querylist() {
        // 1.定义redis中的key
        String key = "cache:shoptype:list";
        // 2.从redis中查询商铺类型缓存
        String shoptypejson = stringRedisTemplate.opsForValue().get(key);
        // 3.判断是否存在，存在直接返回
        if (shoptypejson != null && !shoptypejson.isEmpty()) {
            List<ShopType> shopTypes = JSONUtil.toList(shoptypejson, ShopType.class);
            return Result.ok(shopTypes);
        }
        // 4.不存在，根据id查询数据库
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();
        // 5.判断数据库中是否存在，不存在将商铺信息返回错误信息
        if (typeList == null || typeList.isEmpty()) {
            return Result.fail("商铺类型不存在");
        }
        // 6.数据库中存在，存入商铺信息到redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList),30, TimeUnit.MINUTES);
        return Result.ok(typeList);
    }

}
