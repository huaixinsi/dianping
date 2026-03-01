package com.hmdp;

import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.ExecutorService;

@SpringBootTest
class HmDianPingApplicationTests {

@Resource
private RedisIdWorker redisIdWorker;
private ExecutorService es=java.util.concurrent.Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() {
        Runnable task=()->{
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id="+id);
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
}
