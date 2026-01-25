package com.hmdp.interceptor;


import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 获取 session
        HttpSession session = request.getSession();

        // 获取 当前用户信息
        Object user = session.getAttribute("user");

        // 判断用户是否存在
        if (user == null) {
            // 不存在，拦截，返回 401响应状态码
            response.setStatus(401);
            return false;
        }

        // 存在，保存用户信息到 ThreadLocal
        UserHolder.saveUser((UserDTO) user);

        // 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
