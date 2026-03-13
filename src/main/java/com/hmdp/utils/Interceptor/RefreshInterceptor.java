package com.hmdp.utils.Interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.Constants.RedisConstants;
import com.hmdp.utils.Constants.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;
@Component
@Slf4j
public class RefreshInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;
    //
    public RefreshInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //拦截所有路径的拦截器
        //1.从请求头中获取token值
        String token = request.getHeader(SystemConstants.REQUEST_TOKEN);
        //2.通过token值到redis中查询用户信息
        String key= RedisConstants.LOGIN_USER_KEY+token;
        //获取到用户map对象
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        //判断用户是否存在
        if(!userMap.isEmpty()){
            //3.将用户保存到线程中
            //需要先将用户map对象中转换为userdto对象
            UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
            UserHolder.saveUser(userDTO);
            log.info("用户保存成功，用户为：{}",userDTO);
            //4.刷新token有效期，防止用户登录过期
            stringRedisTemplate.expire(key,RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        }
        //5.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //会话结束删除线程中保存的用户信息
        UserHolder.removeUser();
    }
}
