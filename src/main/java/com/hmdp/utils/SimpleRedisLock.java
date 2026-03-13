package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX="lock:";
    private static final String ID_PREFIX= UUID.randomUUID().toString(true)+"-";
    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate){
        this.name=name;
        this.stringRedisTemplate=stringRedisTemplate;
    }


    @Override
    public boolean tryLock(long timeoutSec) {
        //通过lock再拼接业务名来指定锁名字
        String key=KEY_PREFIX+name;
        //通过uuid创建唯一value再加上当前线程名来确保
        String ThreadId=ID_PREFIX+Thread.currentThread().getId();
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(key, ThreadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(isLock);
    }

    @Override
    public void unLock() {
        //释放锁资源
        //先确保当前线程释放锁是否为自己拿到的锁
        String key=KEY_PREFIX+name;
        String ThreadId=ID_PREFIX+Thread.currentThread().getId();
        String value = stringRedisTemplate.opsForValue().get(key);
        if(ThreadId.equals(value)){
            //释放锁
            stringRedisTemplate.delete(key);
        }
    }
}
