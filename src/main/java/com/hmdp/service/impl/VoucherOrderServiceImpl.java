package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
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
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userID, stringRedisTemplate);

        // 尝试获取锁
        boolean isLock = lock.tryLock(1200);
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
            lock.unLock();
        }
    }

    @Transactional  // 在方法上添加事务
    public Result createVoucherOrder(Long voucherId) {
        // 一人一单
        Long userID = UserHolder.getUser().getId();
        // 查询订单
        // 判断是否存在
        Long count = query().eq("user_id", userID).eq("voucher_id", voucherId).count();
        if(count > 0)
        {
            // 用户已经购买过
            return Result.fail("用户已经购买过");
        }

        // 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")    // set stock = stock - 1    // where voucher_id = ? and stock > 0
                .eq("voucher_id", voucherId).gt("stock", 0)     // 利用了数据库的行锁（乐观锁），保证线程安全
                .update();

        if(!success) {
            return Result.fail("库存不足");
        }

        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单ID
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 用户ID
        voucherOrder.setUserId(userID);
        // 代金券ID
        voucherOrder.setVoucherId(voucherId);

        // 写入数据库，保存订单
        save(voucherOrder);

        // 返回订单id
        return Result.ok(orderId);
    }
}
