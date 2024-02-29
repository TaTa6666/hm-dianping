package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

//    @Override
//    public Result queryById(Long id) {             未解决缓存穿透问题
//        String cache_key = CACHE_SHOP_KEY + id;
//
//        // 1. 从redis查询商铺缓存
//        String shopJSON = stringRedisTemplate.opsForValue().get(cache_key);
//        // 2.判断是否存在
//        if (StrUtil.isNotBlank(shopJSON)){
//            // 3. 存在,直接返回,将shopJSON转为java对象
//            Shop shop = JSONUtil.toBean(shopJSON, Shop.class);
//            return Result.ok(shop);
//        }
//        // 当 shopJSON 是空字符串时也被isNotBlank视为不存在
//        if (shopJSON != null) {
//            // 不是null，说明是空字符串
//            return Result.fail("店铺信息不存在！");
//        }
//
//        // 4. 不存在，根据id查询数据库
//        Shop shop = getById(id);  // mybatis-plus 的 Service层提供的接口
//        // 5. 数据库也不存在，返回错误。 直接返回存在 “缓存穿透” 问题，解决：向redis中添加一个null
//        if (shop == null){
//            stringRedisTemplate.opsForValue().set(cache_key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return Result.fail("店铺不存在");
//            // 也可以返回一个空ok，基于和前端的约定
//        }
//        // 6. 数据库存在，写入redis
//        stringRedisTemplate.opsForValue().set(cache_key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        // 7。返回
//        return Result.ok(shop);
//    }


    @Override
    public Result queryById(Long id) {
        // 缓存穿透
       // Shop shop = cacheClient.queryByPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById/*传的是方法*/, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        // 逻辑过期 解决 缓存击穿
        // 使用逻辑过期需要提前在redis存好数据，比如在测试前先调用saveShop2Redis方法进行缓存预热
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL,TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    // 缓存穿透   使用空字符串  （布隆过滤器）
/*    public Shop queryByPassThrough(Long id){
        String cache_key = CACHE_SHOP_KEY + id;

        // 1. 从redis查询商铺缓存
        String shopJSON = stringRedisTemplate.opsForValue().get(cache_key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJSON)){
            // 3. 存在,直接返回,将shopJSON转为java对象
            return JSONUtil.toBean(shopJSON, Shop.class);
        }
        // 当 shopJSON 是空字符串时也被isNotBlank视为不存在
        if (shopJSON != null) {
            // 不是null，说明是空字符串
            return null;
        }

        // 4. 不存在，根据id查询数据库
        Shop shop = getById(id);  // mybatis-plus 的 Service层提供的接口
        // 5. 数据库也不存在，返回错误。 直接返回存在 “缓存穿透” 问题，解决：向redis中添加一个null
        if (shop == null){
            stringRedisTemplate.opsForValue().set(cache_key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
            // 也可以返回一个空ok，基于和前端的约定
        }
        // 6. 数据库存在，写入redis
        stringRedisTemplate.opsForValue().set(cache_key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7。返回
        return shop;
    }*/

    // 缓存击穿   使用互斥锁   （逻辑过期+互斥锁+新线程创建redis+返回旧redis）
    //                   ---- 单使用互斥锁会让其他线程休眠一会，然后递归去检查是否可以获取互斥锁，one by one进行，可保证一致性
/*    public Shop queryWithMutex(Long id){
        String cache_key = CACHE_SHOP_KEY + id;

        // 1. 从redis查询商铺缓存
        String shopJSON = stringRedisTemplate.opsForValue().get(cache_key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJSON)){
            // 3. 存在,直接返回,将shopJSON转为java对象
            return JSONUtil.toBean(shopJSON, Shop.class);
        }
        // 当 shopJSON 是空字符串时也被isNotBlank视为不存在
        if (shopJSON != null) {
            // 不是null，说明是空字符串
            return null;
        }


        // 实现缓存重建
        // 4.1  尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;  // mybatis-plus 的 Service层提供的接口 说的是下面的getById
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2  判断是否获取成功
            if (!isLock) {
                // 4.3  失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);

            }
            // 4.4  成功，根据id查询数据库
            shop = getById(id);
            // 模拟重建的延时
            Thread.sleep(200);
            // 5. 不存在，返回错误
            if (shop == null) {
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(cache_key, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6 存在，写入redis
            stringRedisTemplate.opsForValue().set(cache_key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 7 释放互斥锁
            unlock(lockKey);
        }

        return shop;
    }*/

    // 创建一个 固定的线程池，有10个线程对象
//    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

   // 缓存击穿  逻辑过期
/*     public Shop queryWithLogicalExpire(Long id){
        // 1。 拿到缓存的key
        String cache_key = CACHE_SHOP_KEY + id;
        // 2. 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(cache_key);
        // 3. 判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 不存在，直接返回    -------  为什么redis不存在就直接返回null，而不是像缓存穿透那样，查数据库再加入redis？
            //                     ----  因为会“缓存预热” 存在的数据一定会预热进redis，redis中没有说明真没有
            return null;
        }
        // 4.存在，判断缓存是否逻辑过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);   // 此方法会将RedisData中的Object类映射为JSONObject
        Shop shop = JSONUtil.toBean ((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (LocalDateTime.now().isBefore(expireTime)){
            // 未过期， 返回商铺信息
            return shop;
        }
        // 5.过期，重建缓存，尝试获取互斥锁，成功与否都会返回shop信息
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)){  // 获取成功
            //  6. 获取到互斥锁，开启独立线程：从数据库读取数据；存入redis；释放互斥锁。
            // 不单独创建线程，会有性能损耗，使用线程池
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }

            });
        }
        // 获取互斥锁失败 || 重建缓存完毕
        return shop;
    }*/

 /*   private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    // 缓存预热
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1 查询店铺数据
        Shop shop = getById(id);
        // 模拟延迟
        Thread.sleep(200);
        // 2 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
*/

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
