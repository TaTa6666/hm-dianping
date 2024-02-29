package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 普通的设置过期时间
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    // 设置逻辑过期
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 缓存穿透   使用空字符串  （布隆过滤器）
    public <R, ID> R queryByPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String cache_key = keyPrefix + id;

        // 1. 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(cache_key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)){
            // 3. 存在,直接返回,将shopJSON转为java对象
            return JSONUtil.toBean(json, type);
        }
        // 当 shopJSON 是空字符串时也被isNotBlank视为不存在
        if (json != null) {
            // 不是null，说明是空字符串
            return null;
        }

        // 4. 不存在，根据id查询数据库
        R r = dbFallback.apply(id);  // 因为 R 是不确定的，所以无法直接调用数据库，于是由 函数调用者 传入 数据库查询方法dbFallback
        // 5. 数据库也不存在，返回错误。 直接返回存在 “缓存穿透” 问题，解决：向redis中添加一个null
        if (r == null){
            stringRedisTemplate.opsForValue().set(cache_key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
            // 也可以返回一个空ok，基于和前端的约定
        }
        // 6. 数据库存在，写入redis
        set(cache_key, r, time, unit);
        // 7。返回
        return r;
    }

    // 创建一个 固定的线程池，有10个线程对象
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 缓存击穿  逻辑过期
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        // 1。 拿到缓存的key
        String cache_key = keyPrefix + id;
        // 2. 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(cache_key);
        // 3. 判断是否存在
        if (StrUtil.isBlank(json)) {
            // 不存在，直接返回    -------  为什么redis不存在就直接返回null，而不是像缓存穿透那样，查数据库再加入redis？
            //                     ----  因为会“缓存预热” 存在的数据一定会预热进redis，redis中没有说明真没有
            return null;
        }
        // 4.存在，判断缓存是否逻辑过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);   // 此方法会将RedisData中的Object类映射为JSONObject
        R r = JSONUtil.toBean ((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (LocalDateTime.now().isBefore(expireTime)){
            // 未过期， 返回商铺信息
            return r;
        }
        // 5.过期，重建缓存，尝试获取互斥锁，成功与否都会返回shop信息
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)){  // 获取成功
            //  6. 获取到互斥锁，开启独立线程：从数据库读取数据；存入redis；释放互斥锁。
            // 不单独创建线程，会有性能损耗，使用线程池
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    // 查数据库
                    R r1 = dbFallback.apply(id);
                    // 写入redis
                    this.setWithLogicalExpire(cache_key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }

            });
        }
        // 获取互斥锁失败 || 重建缓存完毕
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }




}
