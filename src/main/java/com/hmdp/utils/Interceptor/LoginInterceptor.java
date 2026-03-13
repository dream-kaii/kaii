package com.hmdp.utils.Interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //拦截需要登录的路径所需要的拦截器
        //从threadlocal中获取用户存在则放行
        UserDTO user = UserHolder.getUser();
        if(user==null){
            //不存在则拦截 并给前端返回异常的状态码
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
