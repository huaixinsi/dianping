package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
    //通过注解的方式声明队列和交换机
    @RabbitListener(bindings = @QueueBinding(
            value =/* 声明队列以及是否持久化*/@Queue(name = "dianping.queue", durable = "true"),
            exchange =/* 声明交换机和类型*/@Exchange(name = "dianping.exchange", type = "direct"),
            key = {"dianping.key"/* 路由键 */ }
    ))
    public void receiveMessage(String message) {
        System.out.println("Received message: " + message);
    }
}
