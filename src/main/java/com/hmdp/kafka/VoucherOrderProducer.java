package com.hmdp.mapper.kafka;


import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.hmdp.entity.VoucherOrder;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/*
优惠劵消息生产者

*/

@Component
public class VoucherOrderProducer {
    @Resource
    private KafkaTemplate<String,String> kafkaTemplate;

    private static final String TOPIC = "coupon-order-topic";

    //发送优惠劵消息 进行扣减库存
    public void sendCouponOrder(Long userId,Long orderId,Long voucherId){
        //构建消息对象
        VoucherOrder voucherOrder=new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId);
        //将对象转成JSON格式进行发送
        String message = JSON.toJSONString(voucherOrder);
        //发送消息
        kafkaTemplate.send(TOPIC,orderId.toString(),message).addCallback(
                success -> System.out.println("✅ 消息发送成功: " + orderId),
                ex -> System.err.println("❌ 消息发送失败: " + ex.getMessage())
        );
    }

}
