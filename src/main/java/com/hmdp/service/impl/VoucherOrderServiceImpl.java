package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    // 创建代理对象
    private IVoucherOrderService proxy;

    // 加载释放锁的脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 创建阻塞队列
    // private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    // 创建线程池
    //private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 类初始化的时候执行线程池
   /* @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }*/

    // 线程任务 (基于 stream 消息队列)
    private class VoucherOrderHandler implements Runnable {
        // 消息队列名称
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    // 获取消息队列中的订单信息 xreadgroup group g1 c1 count 1 block 2000 streams streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    // 判断消息获取是否成功
                    if(list == null || list.isEmpty()) {
                        // 获取失败，没有消息，继续下一次循环
                        continue;
                    }

                    // 解析消息信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    // 获取成功，可以下单
                    handleVoucherOrder(voucherOrder);

                    // ACK 确认 sack stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理异常订单", e);
                    handlePendingList();
                }
            }
        }

        // 处理 pending list 中的消息 的函数
        private void handlePendingList() {
            while (true) {
                try {
                    // 获取 pending-list 中的订单信息 xreadgroup group g1 c1 count 1 streams streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    // 判断消息获取是否成功
                    if(list == null || list.isEmpty()) {
                        // 获取失败，pending-list 没有异常消息，结束循环循环
                        break;
                    }

                    // 解析消息信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    // 获取成功，可以下单
                    handleVoucherOrder(voucherOrder);

                    // ACK 确认 sack stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理 pending-list 异常订单", e);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }


    // 线程任务 (基于 JVM 阻塞队列)
    /*private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 获取阻塞队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 创建订单
                    handleVoucherOrder(voucherOrder);

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }

                // 创建订单
            }
        }
    }*/

    // 创建订单的函数
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        // 尝试获取锁
        boolean isLock = lock.tryLock();
        if(!isLock)
        {
            // 获取锁失败，返回错误
            log.error("不允许重复下单");
            return;
        }

        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    // 创建订单的函数具体逻辑
    @Transactional  // 在方法上添加事务
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单
        Long userID = voucherOrder.getUserId();
        // 查询订单
        // 判断是否存在
        Long count = query().eq("user_id", userID).eq("voucher_id", voucherOrder).count();
        if(count > 0)
        {
            // 用户已经购买过
            log.error("用户已经购买过一次！");
            return;
        }

        // 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")    // set stock = stock - 1    // where voucher_id = ? and stock > 0
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)     // 利用了数据库的行锁（乐观锁），保证线程安全
                .update();

        if(!success) {
            log.error("库存不足！");
            return;
        }

        // 写入数据库，保存订单
        save(voucherOrder);
    }

    //  基于 Redis 消息队列 streams 实现
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 生成订单ID
        Long orderId = redisIdWorker.nextId("order");

        // 执行 lua 脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), orderId.toString()
        );

        int r = result.intValue();

        // 判断结果是否为0
        if(r != 0) {
            // 不为0，没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 返回订单ID
        return Result.ok(orderId);
    }

    // 判断秒杀资格，并下达异步线程创建订单 (利用 JVM 的阻塞队列)
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();

        // 执行 lua 脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );

        int r = result.intValue();

        // 判断结果是否为0
        if(r != 0) {
            // 不为0，没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 为 0，有购买资格，把下单信息保存到阻塞队列
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单ID
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 用户ID
        voucherOrder.setUserId(userId);
        // 代金券ID
        voucherOrder.setVoucherId(voucherId);

        // 保存阻塞队列
        orderTasks.add(voucherOrder);

        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 返回订单ID
        return Result.ok(orderId);
    }*/

    // 基于 Redis 分布式锁版本3 实现
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠卷信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 判断秒杀是否开始
        LocalDateTime beginTime = voucher.getBeginTime();
        if(beginTime.isAfter(LocalDateTime.now()))
        {
            return Result.fail("秒杀尚未开始");
        }
        // 判断秒杀是否结束
        LocalDateTime endTime = voucher.getEndTime();
        if(endTime.isBefore(LocalDateTime.now()))
        {
            return Result.fail("秒杀已结束");
        }
        // 判断库存是否充足
        Integer stock = voucher.getStock();
        if(stock < 1)
        {
            return Result.fail("库存不足");
        }

        Long userID = UserHolder.getUser().getId();
        // 创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userID, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userID);

        // 尝试获取锁
        boolean isLock = lock.tryLock();
        if(!isLock)
        {
            // 获取锁失败，返回错误
            return Result.fail("不允许重复下单");
        }

        try {
            // 获取代理对象 （事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }*/
}
