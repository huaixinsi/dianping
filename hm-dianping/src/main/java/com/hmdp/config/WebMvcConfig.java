package com.hmdp.config;

import com.hmdp.interceptor.LoginInterceptor;
import com.hmdp.interceptor.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
        @Resource
        private StringRedisTemplate stringRedisTemplate;
        //注册自定义拦截器
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new LoginInterceptor())
                    .excludePathPatterns("/user/code",
                            "/blog/hot",
                            "/shop/**",
                            "/voucher/**",
                            "upload/**",
                            "/user/login",
                            "/doc.html",
                            "/webjars/**",
                            "/v3/api-docs/**",
                            "/swagger-resources/**",
                            "/swagger-ui/**").order(1);

                registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").addPathPatterns("/**")
                    // 排除 Knife4j/Swagger 所有路径
                    .excludePathPatterns(
                            "/doc.html",
                            "/webjars/**",
                            "/v3/api-docs/**",
                            "/swagger-resources/**",
                            "/swagger-ui/**").order(0);
        }


}
