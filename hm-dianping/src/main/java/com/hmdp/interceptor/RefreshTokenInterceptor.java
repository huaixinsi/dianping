package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.checkerframework.checker.nullness.compatqual.NullableDecl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

public class RefreshTokenInterceptor implements HandlerInterceptor {private StringRedisTemplate stringRedisTemplate;
    public RefreshTokenInterceptor (StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取请求头里的token
        String token=request.getHeader("authorization");
        if(token==null||token.trim().isEmpty()){
            return true;
        }
        //2.获取redis里的用户信息
        Map<Object,Object> user=stringRedisTemplate.opsForHash().entries(token);
        //3.判断用户信息是否存在
        if (user.isEmpty()) {
            return true;
        }
        //4.保存用户信息到ThreadLocal
        UserDTO userDTO=BeanUtil.fillBeanWithMap(user, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        //5.刷新token有效期
        stringRedisTemplate.expire(token,30,java.util.concurrent.TimeUnit.MINUTES);
        //6..存在，放行
        return true;
    }

    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @NullableDecl Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
