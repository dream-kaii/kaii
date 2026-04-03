package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.Constants.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static com.hmdp.utils.Constants.RedisConstants.LOCK_ORDER_KEY;
import static com.hmdp.utils.Constants.RedisConstants.ORDER_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private SeckillVoucherServiceImpl seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private KafkaTemplate kafkaTemplate;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //创建一个阻塞队列
    //private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024 * 1024);
    //创建一个单线程的异步处理池，用来处理创建订单
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();
    //在类初始化时会执行这个方法 然后用线程池提交阻塞队列处理订单
    @PostConstruct
    public void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        private final String queueName="stream.orders";
        @Override
        public void run() {
            while (true){
                try {
                    //1.获取消息队列中的信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息是否获取成功
                    if(records==null||records.isEmpty()){
                        continue;
                    }
                    //3.解析消息中的订单信息
                    MapRecord<String, Object, Object> entries = records.get(0);
                    //第一个String为消息id
                    Map<Object, Object> value = entries.getValue();
                    //通过订单信息，创建订单
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    proxy.createVoucherOrder(voucherOrder);
                    //3.ack确认消息
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",entries.getId());
                } catch (Exception e) {
                    log.debug("订单处理异常！",e);
                    handlerPendingList();
                }

            }
        }

        private void handlerPendingList()  {
            //队列中报异常后去pendingList中查询是否有消费未处理的信息
            while (true){
                try {
                    //1.获取Pending-List中的信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断消息是否获取成功
                    if(records==null||records.isEmpty()){
                        //如果为空则说明没有异常信息了直接退出循环
                        break;
                    }
                    //3.解析消息中的订单信息
                    MapRecord<String, Object, Object> entries = records.get(0);
                    //第一个String为消息id
                    Map<Object, Object> value = entries.getValue();
                    //通过订单信息，创建订单
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    proxy.createVoucherOrder(voucherOrder);
                    //3.ack确认消息
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",entries.getId());
                } catch (Exception e) {
                    log.debug("处理Pending-list异常",e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }

        }


    }
   /* private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    //获取阻塞队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handlerVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常!",e);
                }
            }
        }


    }*/
    private IVoucherOrderService proxy;
    public void handlerVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户ID
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //创建锁对象
        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
        boolean isLock = lock.tryLock();
        if(!isLock){
            //没有拿到锁，直接返回
            log.error("不允许重复下单!");
            return;
        }
        try {
            //保存用户订单
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            //最后释放锁
            lock.unlock();
        }

    }
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextID(ORDER_KEY);
        //通过异步操作来优化一人一单及秒杀抢购
        //先执行lua脚本判断是否有资格来下单
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        log.info("lua脚本结果为：{}",result);
        int r = result.intValue();
        //若result不为0则报异常
        if(r!=0){
            return Result.fail(r==1 ? "库存不足" : "不能重复下单");
        }
        //获取当前线程对象，异步通过消息队列执行保存订单需求
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //创建一个订单
        VoucherOrder voucherOrder=new VoucherOrder();
        //设置订单id
        voucherOrder.setId(orderId);
        //设置用户ID
        voucherOrder.setUserId(userId);
        //设置代金劵id
        voucherOrder.setVoucherId(voucherId);
        //使用kafka消息队列异步执行保存扣减库存订单需求
        kafkaTemplate.send("voucher-orders",  voucherOrder);
        //返回订单ID
        return Result.ok(orderId);
    }




    /*@Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextID(ORDER_KEY);
        //通过异步操作来优化一人一单及秒杀抢购
        //先执行lua脚本判断是否有资格来下单
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        log.info("lua脚本结果为：{}",result);
        int r = result.intValue();
        //若result不为0则报异常
        if(r!=0){
            return Result.fail(r==1 ? "库存不足" : "不能重复下单");
        }
        //获取当前线程对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //进行异步阻塞队列
        VoucherOrder voucherOrder=new VoucherOrder();
        //设置订单id
        voucherOrder.setId(orderId);
        //设置用户ID
        voucherOrder.setUserId(userId);
        //设置代金劵id
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列中
        orderTasks.add(voucherOrder);
        //返回订单ID
        return Result.ok(orderId);
    }*/


    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //秒杀优惠劵处理方案
        //一般更新操作采用乐观锁，即版本号法 CAS(但要避免ABA问题)

        //1.查询秒杀优惠劵
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否过期
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀还未开始！");
        }
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束！");
        }
        //3.判断库存是否充足
        if(voucher.getStock()<0){
            return Result.fail("库存不足！");
        }*/
        /*//4.扣减库存 update into table set stock=stock-1 where id=id stock>0
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if(!success){
            return Result.fail("库存不足！");
        }
        //5.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //5.1创建全局ID
        long id = redisIdWorker.nextID(ORDER_KEY);
        voucherOrder.setId(id);
        //5.2用户id
        Long UserId = UserHolder.getUser().getId();
        voucherOrder.setUserId(UserId);
        //5.3秒杀劵id
        voucherOrder.setVoucherId(voucherId);
        //5.4保存订单
        save(voucherOrder);
        //6.返回订单id
        return Result.ok(id);*/
        //需要提供锁对象且唯一
        //但这种方法只针对同一个jvm中 当有集群时并不适用
        /*synchronized (UserHolder.getUser().getId().toString().intern()){
            //this调用的方法为当前类的对象，并不是spring所管理的代理对象。则会导致事物管理失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }*/
        //对于集群jvm通过分布式锁来上锁
        //先获取锁对象
        /*SimpleRedisLock simpleRedisLock=new SimpleRedisLock("order:"+UserHolder.getUser().getId(),stringRedisTemplate);
        boolean isLock = simpleRedisLock.tryLock(3000L);*/
        //通过redisson来获取锁
        /*RLock lock = redissonClient.getLock("order:" + UserHolder.getUser().getId());
        boolean isLock = lock.tryLock();
        if (!isLock){
            return Result.fail("不允许重复下单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            //最后释放锁
            lock.unlock();
        }
    }*/

    //实现一人一单要求，对于秒杀劵来说，通常我们要求一个账号只能抢购一单
    //对于此类插入操作，我们通常采用悲观锁来完成，即对于sql操作进行事物管理，对于查询订单创建订单采用sy锁来控制
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        Long voucherId = voucherOrder.getVoucherId();
        //4.扣减库存 update into table set stock=stock-1 where id=id stock>0
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        //5.4保存订单 到数据库中
        save(voucherOrder);
    }
}
