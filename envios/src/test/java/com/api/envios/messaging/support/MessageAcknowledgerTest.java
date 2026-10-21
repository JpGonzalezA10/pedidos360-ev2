package com.api.envios.messaging.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.api.envios.exception.ExternalServiceUnavailableException;
import com.api.envios.exception.ResourceNotFoundException;
import com.api.envios.messaging.config.MessagingProperties;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/*
 * Pruebas de la politica de confirmacion manual: ACK, reintento diferido y envio a DLQ.
 */
class MessageAcknowledgerTest {

    private static final long TAG = 7L;
    private static final String RETRY_QUEUE = "envios.pedido-confirmado.retry";

    private RabbitTemplate rabbitTemplate;
    private Channel channel;
    private MessageAcknowledger acknowledger;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        channel = mock(Channel.class);
        MessagingProperties properties = new MessagingProperties(
                new MessagingProperties.Exchanges("envios.events", "pedidos.events", "envios.dlx"),
                new MessagingProperties.RoutingKeys("a", "b", "c", "d"),
                new MessagingProperties.Queues("q", RETRY_QUEUE, "dlq", "q2", "r2", "d2"),
                new MessagingProperties.Retry(3, 5000),
                new MessagingProperties.Dlq(60000, 100));
        acknowledger = new MessageAcknowledger(rabbitTemplate, properties);
    }

    @Test
    void ackConfirmsDeliveryTag() throws Exception {
        acknowledger.ack(channel, message(0));

        verify(channel).basicAck(TAG, false);
    }

    @Test
    void permanentErrorIsRejectedWithoutRequeueToDeadLetter() throws Exception {
        acknowledger.handleFailure(channel, message(0), new ResourceNotFoundException("no existe"), RETRY_QUEUE);

        verify(channel).basicNack(TAG, false, false);
        verify(rabbitTemplate, never()).send(any(String.class), any(String.class), any(Message.class));
    }

    @Test
    void transientErrorIsScheduledInRetryQueueAndOriginalAcked() throws Exception {
        acknowledger.handleFailure(channel, message(0),
                new ExternalServiceUnavailableException("pedidos caido", new RuntimeException("timeout")), RETRY_QUEUE);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(""), eq(RETRY_QUEUE), captor.capture());
        assertThat((Integer) captor.getValue().getMessageProperties()
                .getHeader(MessageAcknowledger.RETRY_COUNT_HEADER)).isEqualTo(1);
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void transientErrorAfterMaxAttemptsGoesToDeadLetter() throws Exception {
        acknowledger.handleFailure(channel, message(2),
                new ExternalServiceUnavailableException("pedidos caido", new RuntimeException("timeout")), RETRY_QUEUE);

        verify(channel).basicNack(TAG, false, false);
        verify(rabbitTemplate, never()).send(any(String.class), any(String.class), any(Message.class));
    }

    private Message message(int previousRetries) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(TAG);
        props.setConsumerQueue("envios.pedido-confirmado.queue");
        if (previousRetries > 0) {
            props.setHeader(MessageAcknowledger.RETRY_COUNT_HEADER, previousRetries);
        }
        return new Message("{}".getBytes(), props);
    }
}
