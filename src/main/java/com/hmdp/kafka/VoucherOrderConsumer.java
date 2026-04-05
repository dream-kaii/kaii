package com.hmdp.kafka;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.listener.Topic;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component

public class VoucherOrderConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedissonClient redissonClient;

    @KafkaListener(topics = "coupon-order-topic",groupId = "coupon-order-group")
    public void handlerVoucherOrder(VoucherOrder voucherOrder){
        //获取用户id
        Long userId = voucherOrder.getUserId();
        //创建分布式锁
        RLock lock = redissonClient.getLock("Lock:order:" + userId);
        //获取锁
        boolean tryLock = lock.tryLock();
        if(!tryLock){
            return;
        }
        //创建订单到数据库中并扣减库存
        try {
            voucherOrderService.createVoucherOrder(voucherOrder);
        }catch (Exception e){

        }finally {
            lock.unlock();
        }


    }
}
