package com.hmdp.consumer;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

@Slf4j
@Component
public class SeckillConsumer {
    @Autowired
    private IVoucherOrderService voucherOrderService;

    //声明交换机和对象
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "seckill.queue",durable = "true"),
            exchange = @Exchange(value = "seckillOrderExchange",type = ExchangeTypes.DIRECT),
            key = "seckill")
    )
    public void consume(VoucherOrder voucherOrder, Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws Exception{
        // 这里可以直接处理 VoucherOrder 对象
        log.info("Received order: {}", voucherOrder);
        try {
            voucherOrderService.getResult(voucherOrder);
            //tag 是消息的唯一标识，手动确认消息
            channel.basicAck(tag, false);//每个参数作用：tag：消息的唯一标识；multiple：是否批量确认
        }
        catch (Exception e) {
            log.error("Error processing order: {}", e.getMessage());
            // 可以选择重试或者记录失败订单等
            channel.basicNack(tag, false, true); // 重新入队，tag：消息的唯一标识；multiple：是否批量确认；requeue：是否重新入队
        }

    }
}
