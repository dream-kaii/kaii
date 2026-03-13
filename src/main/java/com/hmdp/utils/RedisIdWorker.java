package com.hmdp.utils;

//全局ID生成器  可以用来作为订单号
//通过redis自增并拼接一些其他信息

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    //ID组成部分 采用64位，其中首位为符号位，保持0不变
    //除符号位前31位为时间戳，以秒为单位若指定起始时间为2000 01 01 00可以用69年
    //后32位为序列号
    /*
    开始时间戳
    * */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    private static final  int COUNT_BITS = 32;


    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }

    public long nextID(String keyPrefix){
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond-BEGIN_TIMESTAMP;

        //2.生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+date);

        return timestamp << COUNT_BITS | count;
    }

}
