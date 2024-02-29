package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {

        String cache_key = "ShopTypeList";

        // 1. 从redis查询typelist缓存
        String  shopTypeListJson = stringRedisTemplate.opsForValue().get(cache_key);
        //2. 在redis中存在，则直接返回
        if (StrUtil.isNotBlank(shopTypeListJson)){
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeListJson, ShopType.class);
            return Result.ok(shopTypeList);
        }
        // 3. redis中没有当前缓存，则需要查数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 4.再判断数据库中是否存在
        //  不存在或者是空的，直接返回空的
        if (typeList == null || typeList.isEmpty()){
            return Result.ok();
        }
        // 存在且有数据，写入redis
        stringRedisTemplate.opsForValue().set(cache_key, JSONUtil.toJsonStr(typeList));
        return Result.ok(typeList);
    }
}
