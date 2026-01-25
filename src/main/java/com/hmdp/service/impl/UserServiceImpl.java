package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.检验手机号码
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 手机号不符合
            return Result.fail("手机号码格式错误！");
        }

        // 2.手机号符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 3.保存验证码到session
        session.setAttribute("code", code);

        // 4. 发送验证码
        log.debug("发送的验证码成功，验证码：{}", code);

        // 5.返回结果
        return Result.ok();
    }
}
