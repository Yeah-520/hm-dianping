package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据指定的key查询缓存，并通过dbFallback获取数据并写入缓存
     *
     * @param keyPrefix  缓存key的前缀
     * @param id         缓存key的后缀
     * @param type       返回对象的类型
     * @param dbFallback 获取数据的函数
     * @param time       缓存的过期时间
     * @param unit       时间单位
     * @param <R>        返回对象的类型
     * @param <ID>       缓存key的类型
     * @return 缓存中的数据或通过dbFallback获取的数据
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        String Json = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isNotBlank(Json)) {
            return JSONUtil.toBean(Json, type);
        }

        if (Json == null) {
            return null;
        }

        R r = dbFallback.apply(id);

        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        set(key, r, time, unit);

        return r;
    }

    /**
     * 根据指定的key查询缓存，并通过dbFallback获取数据并写入缓存
     *
     * @param keyPrefix  缓存key的前缀
     * @param id         缓存key的后缀
     * @param type       返回对象的类型
     * @param dbFallback 获取数据的函数
     * @param time       缓存的过期时间
     * @param unit       时间单位
     * @param <R>        返回对象的类型
     * @param <ID>       缓存key的类型
     * @return 缓存中的数据或通过dbFallback获取的数据
     */
    public <R, ID> R handleCacheBreakdownByLogicalExpire(String keyPrefix, String lockKeyPrefix,
                                                         ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        String json = stringRedisTemplate.opsForValue().get(key);
        // 判断缓存是否命中（由于是热点数据，提前进行缓存预热，默认缓存一定会命中）
        if (StrUtil.isBlank(json)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);

        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return r;
        }

        String lockKey = lockKeyPrefix + id;
        if (tryLock(lockKey)) {
            // 在线程1重建缓存期间，线程2进行过期判断，假设此时key是过期状态，线程1重建完成并释放锁，线程2立刻获取锁，并启动异步线程执行重建，那此时的重建就与线程1的重建重复了
            // 因此需要在线程2获取锁成功后，在这里再次检测redis中缓存是否过期（DoubleCheck），如果未过期则无需重建缓存，防止数据过期之后，刚释放锁就有线程拿到锁的情况，重复访问数据库进行重建
            json = stringRedisTemplate.opsForValue().get(key);
            // 缓存命中，先把json反序列化为逻辑过期对象
            redisData = JSONUtil.toBean(json, RedisData.class);

            r = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
            // 判断是否逻辑过期
            if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                return r;
            }
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1 = dbFallback.apply(id);
                    setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }

        return r;
    }

    /**
     * 尝试获取锁
     *
     * @param key 锁的key
     * @return 是否获取成功
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key 锁的key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}

















