package com.hmdp;

import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.ExecutorService;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;
    private ExecutorService es = java.util.concurrent.Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() {
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id=" + id);
            }
        };
        //启动100个线程，模拟高并发环境
        for (int i = 0; i < 100; i++) {
            es.submit(task);
        }
        try {
            es.awaitTermination(1, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testRabbit() {
        String queueName = "dianping.queue";
        String message = "Hello, RabbitMQ!";
        rabbitTemplate.convertAndSend(queueName, message);

    }
}
