package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

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
        // 检验手机号码
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 手机号不符合
            return Result.fail("手机号码格式错误！");
        }

        // 手机号符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 保存验证码到session
        session.setAttribute("code", code);

        // 发送验证码
        log.debug("发送的验证码成功，验证码：{}", code);

        // 返回结果
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        // 检验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 手机号不符合
            return Result.fail("手机号码格式错误！");
        }

        // 检验验证码
        String code = loginForm.getCode();
        Object cacheCode = session.getAttribute("code");
        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            return Result.fail("验证码错误！");
        }

        // 根据手机号查询用户
        User user = query().eq("phone", phone).one();

        // 判断用户是否存在
        if (user == null) {
            // 5.不存在，创建新用户，并保存到数据库
            user = createUserWithPhone(phone);
        }

        // 保存用户到 session
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        // 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 保存用户
        save(user);
        return user;
    }
}
