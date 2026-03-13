package com.hmdp;

import com.hmdp.HmDianPingApplication;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest(classes = HmDianPingApplication.class)
public class HmDianPingApplicationTests {
    @Resource
    public ShopServiceImpl shopService;
    @Resource
    public StringRedisTemplate stringRedisTemplate;
    @Test
    public void test01(){
        //获得所有店铺
        List<Shop> list = shopService.list();
        //根据店铺类型分组
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //分批次写入redis中
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //根据店铺类型写入
            Long shopType = entry.getKey();
            //
            List<Shop> shops = entry.getValue();
            String key="shop:geo:"+shopType;
            //创建一个指定为geo类型的集合用来装店铺然后请求一次进行存储
            List<RedisGeoCommands.GeoLocation<String>> location = new ArrayList<>(shops.size());

            for (Shop shop : shops) {
                location.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                ));

            }

            stringRedisTemplate.opsForGeo().add(key,location);

        }


    }

    @Test
    public void testHyperLogLog(){
        String[] values=new String[1000];
        int j=0;
        for (int i = 0; i < 1000000; i++) {
            j=j%1000;
            values[j]="user_id"+i;
            if(j==999){
                stringRedisTemplate.opsForHyperLogLog().add("hl1",values);
            }
            j++;
        }
        //统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl1");
        System.out.println("hl1:"+count);
        //1003579
    }
}
