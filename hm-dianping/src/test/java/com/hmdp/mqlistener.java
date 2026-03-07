package com.hmdp;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class mqlistener {

    @RabbitListener(queues = "dianping.queue")
    public void receiveMessage(String message) {
        log.info("Received message: {}", message);

    }
}

