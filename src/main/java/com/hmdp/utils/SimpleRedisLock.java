package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    // 锁的名称
    private String name;

    // 前缀
    private static final String KEY_PREFIX = "lock:";

    private StringRedisTemplate stringRedisTemplate;

    /**
     * 创建锁的构造器
     * @param name
     * @param stringRedisTemplate
     */
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取当前线程的标识
        long threadId = Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId+ "", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);    // 避免装箱问题，空指针
    }

    @Override
    public void unLock() {
        // 释放锁
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
