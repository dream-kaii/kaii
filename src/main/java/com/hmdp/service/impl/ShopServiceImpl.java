package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.Constants.RedisConstants;
import com.hmdp.utils.Constants.SystemConstants;
import com.hmdp.utils.RedisData;
import lombok.val;
import org.apache.tomcat.jni.Time;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.domain.geo.GeoShape;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.Constants.RedisConstants.*;

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
    /*@Override
    缓存穿透解决办法：
    public Result queryShopById(Long id) {
        //根据店铺id查询详情
        //先在redis中查询
        String key=CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        if(shopJson!=null){
            //说明数据为“”，直接返回错误信息
            return Result.fail(SHOP_NULL);
        }
        //不存在则在数据库中查询
        Shop shop = getById(id);
        if(shop==null){
            //若数据库中不存在则返回错误信息
            //解决缓存穿透问题，若数据库查询为null则将存入redis中，并设置有效期
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return Result.fail(SHOP_NULL);
        }
        //查询内容保存在redis中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
        //设置redis缓存时间为30分钟
        stringRedisTemplate.expire(key,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }*/


    @Override
    //缓存击穿解决办法：
    //缓存击穿即当redis中某个热点key过期时，导致大量访问到数据库引发的问题
    //1：通过互斥锁来解决  当key过期时，当前线程拿到锁进行缓存重建，其他后面来访问的线程进行线程休眠
    public Result queryShopById(Long id) {
        String key=CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        if(shopJson!=null){
            //说明数据为“”，直接返回错误信息
            return Result.fail(SHOP_NULL);
        }
        //不存在，则先拿锁
        String lockKey=LOCK_SHOP_KEY+id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            if(!isLock){
                //如果没拿到锁，则进行线程休眠
                Thread.sleep(50);
                //进行递归，直到拿到锁，不过有概率导致栈溢出
                this.queryShopById(id);
            }
            //拿到锁，查询数据库，进行缓存重建
            //当拿到锁后要进行doublecheck,以防上一个线程已进行了缓存重建
            shop = doubleCheck(id);
            if(shop!=null){
                return Result.ok(shop);
            }
            shop = getById(id);
            if(shop==null){
                //若数据库中不存在则返回错误信息
                //解决缓存穿透问题，若数据库查询为null则将存入redis中，并设置有效期
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return Result.fail(SHOP_NULL);
            }
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁资源
            unLock(lockKey);
        }

        return Result.ok(shop);
    }
    //第二种 解决缓存击穿方法
    //利用逻辑过期：
    //所谓逻辑过期，即不会在redis中真正的设置一个过期时间，而是永久保存
    //定义一个包装对象，将对象封装成一个成员变量，再定义一个时间，这个时间是未来时间，判断当前时间是否大于未来时间
    //利用线程池
    /*private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
    @Override
    public Result queryShopById(Long id) {
        //逻辑过期不能先查询redis中数据，因为一定会拿到，所以得先判断是否过期
        String key=CACHE_SHOP_KEY+id;
        String redisDataJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlankIfStr(redisDataJson)){
            //如果为空则报错
            return Result.fail(SHOP_NULL);
        }
        //解析对象并获得shop
        RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        //判断
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            //不过期，则返回数据
            return Result.ok(shop);
        }
        //过期 还是先拿锁，再去数据库查询
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        if(!isLock){
            //若没拿到，则返回旧数据
            return Result.ok(shop);
        }
        //拿到，进行doublecheck，再查询数据库进行缓存重建
        redisDataJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlankIfStr(redisDataJson)){
            //如果为空则报错
            return Result.fail(SHOP_NULL);
        }
        //解析对象并获得shop
        redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
        shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        //判断
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            //不过期，则返回数据
            return Result.ok(shop);
        }
        CACHE_REBUILD_EXECUTOR.submit(()->{
            //二次检查还是过期数据，则进行缓存重建
            try {
                this.saveShop2Redis(id, LOCK_SHOP_TTL);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unLock(lockKey);
            }
        });
        return Result.ok(shop);
    }*/

    public boolean tryLock(String key){
        //模拟互斥锁上锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1");
        return BooleanUtil.isTrue(flag);
    }
    public void unLock(String key){
        //模拟解锁，防止死锁
        stringRedisTemplate.delete(key);
    }
    public Shop doubleCheck(Long id){
        //拿到锁进行二次检查缓存是否存在对象
        String key=CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if(shopJson!=null){
            //说明数据为“”，直接返回错误信息
            return null;
        }
        return null;
    }

    public void saveShop2Redis(Long id,Long expiredSeconds){
        //缓存重建方法
        String key=CACHE_SHOP_KEY+id;
        Shop shop = getById(id);
        //封装逻辑时间对象
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expiredSeconds));
        //写入redis中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional//添加事物操作保证一致性
    public Result updateShop(Shop shop) {
        //一定要先修改数据库再删除缓存
        // 写入数据库
        updateById(shop);
        //删除redis中的缓存
        Long id = shop.getId();
        String key=CACHE_SHOP_KEY + id;
        stringRedisTemplate.delete(key);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if(x==null||y==null){
            //说明没有传坐标，则进行普通查询
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //
        int from=(current-1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end=current * SystemConstants.DEFAULT_PAGE_SIZE;
        //获取类型key
        String key=SHOP_GEO_KEY+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                GeoShape.byRadius(new Distance(5000)),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        //解析shopid
        if(results==null){
            return Result.fail("无店铺信息！");
        }
        //获得店铺
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if (content.isEmpty()||content==null){
            return Result.fail("无店铺信息2！");
        }
        ArrayList<Long> ids=new ArrayList<>(content.size());
        Map<String,Distance> distanceMap=new HashMap<>(content.size());
        if(content.size()<from){
            //说明没有店铺了
            return Result.fail("无店铺信息了！");
        }
        //先跳过已查询的店铺
        content.stream().skip(from).forEach(result->{
            //获得店铺ID
            String shopId = result.getContent().getName();
            //添加到集合中
            ids.add(Long.valueOf(shopId));
            //获得店铺距离
            Distance distance = result.getDistance();
            distanceMap.put(shopId,distance);
        });
        //根据id查询店铺
        String idStr=StrUtil.join(",",ids);
        List<Shop> shopList = query().in("id", ids).last("order by field (id," + idStr + ")").list();
        for (Shop shop : shopList) {
            //获得距离对象并赋值
            Distance distance = distanceMap.get(shop.getId().toString());
            shop.setDistance(distance.getValue());
        }

        return Result.ok(shopList);
    }
}
