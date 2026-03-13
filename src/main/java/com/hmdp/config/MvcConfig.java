package com.hmdp.config;

import com.hmdp.utils.Interceptor.LoginInterceptor;
import com.hmdp.utils.Interceptor.RefreshInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Autowired
    private LoginInterceptor loginInterceptor;
    @Autowired
    private RefreshInterceptor refreshInterceptor;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //将两个拦截器添加到springmvc中
        // addPathPatterns("/**")设置拦截路径为全部，order设置拦截器优先级，越小优先级越高默认为0

        registry.addInterceptor(refreshInterceptor).addPathPatterns("/**").order(0);

        registry.addInterceptor(loginInterceptor).excludePathPatterns(
                "/shop/**",
                "/voucher/**",
                "/shop-typr/**",
                "/upload/**",
                "/blog/hot",
                "/user/code",
                "/user/login"
        ).order(1);


    }
}
