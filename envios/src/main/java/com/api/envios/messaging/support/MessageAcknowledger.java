package com.api.envios.messaging.support;

import com.api.envios.exception.BusinessRuleException;
import com.api.envios.exception.ResourceNotFoundException;
import com.api.envios.messaging.config.MessagingProperties;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.stereotype.Component;

/*
 * Centraliza la confirmacion manual (ACK/NACK) de los consumidores.
 *
 * Decision ante un error:
 *  - Error PERMANENTE (payload invalido, recurso inexistente, regla de negocio):
 *      basicNack(requeue=false) -> DLX -> DLQ, sin reintentar.
 *  - Error TRANSITORIO (servicio caido, timeout, base de datos):
 *      se publica una copia en la cola retry (TTL) con x-retry-count+1 y se hace ACK del original.
 *      Al expirar el TTL vuelve a la cola principal. Al agotar los intentos -> NACK -> DLQ.
 *  Nunca se usa requeue=true sobre la cola principal para evitar ciclos infinitos (poison message).
 */
@Component
public class MessageAcknowledger {

    /** Header propio con la cantidad de reintentos ya realizados. */
    public static final String RETRY_COUNT_HEADER = "x-retry-count";

    private static final Logger log = LoggerFactory.getLogger(MessageAcknowledger.class);

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;

    public MessageAcknowledger(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    /** Confirma el procesamiento exitoso: RabbitMQ elimina el mensaje de la cola. */
    public void ack(Channel channel, Message message) throws IOException {
        channel.basicAck(deliveryTag(message), false);
    }

    /** Aplica la ruta de error adecuada segun el tipo de excepcion. */
    public void handleFailure(Channel channel, Message message, Exception error, String retryQueue)
            throws IOException {
        MessageProperties props = message.getMessageProperties();
        int attempt = currentRetryCount(message) + 1;
        int maxAttempts = properties.retry().maxAttempts();

        if (!isRecoverable(error)) {
            sendToDeadLetter(channel, message, "error permanente: " + error.getMessage());
            return;
        }
        if (attempt >= maxAttempts) {
            sendToDeadLetter(channel, message,
                    "reintentos agotados (" + attempt + "/" + maxAttempts + "): " + error.getMessage());
            return;
        }

        try {
            // Copia del mensaje con el contador incrementado hacia la cola de reintento diferido.
            props.setHeader(RETRY_COUNT_HEADER, attempt);
            rabbitTemplate.send("", retryQueue, new Message(message.getBody(), props));
            channel.basicAck(deliveryTag(message), false);
            log.warn("[RETRY] Error transitorio en cola={} intento={}/{} -> {} en {} ms. Causa: {}",
                    props.getConsumerQueue(), attempt, maxAttempts, retryQueue,
                    properties.retry().delayMs(), error.getMessage());
        } catch (RuntimeException publishError) {
            // Si no se pudo agendar el reintento, se devuelve el mensaje a la cola para no perderlo.
            log.error("[RETRY-FALLIDO] No se pudo publicar en {}. Se reencola el original. Causa: {}",
                    retryQueue, publishError.getMessage());
            channel.basicNack(deliveryTag(message), false, true);
        }
    }

    private void sendToDeadLetter(Channel channel, Message message, String reason) throws IOException {
        MessageProperties props = message.getMessageProperties();
        // requeue=false: la cola tiene x-dead-letter-exchange configurado, RabbitMQ lo mueve a la DLQ.
        channel.basicNack(deliveryTag(message), false, false);
        log.error("[DLQ] Mensaje enviado a Dead Letter. cola={} routingKey={} messageId={} motivo={} payload={}",
                props.getConsumerQueue(), props.getReceivedRoutingKey(), props.getMessageId(), reason,
                new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Errores permanentes: reintentar no cambia el resultado. */
    private boolean isRecoverable(Exception error) {
        return !(error instanceof NonRecoverableMessageException
                || error instanceof MessageConversionException
                || error instanceof BusinessRuleException
                || error instanceof ResourceNotFoundException
                || error instanceof IllegalArgumentException);
    }

    private int currentRetryCount(Message message) {
        Object value = message.getMessageProperties().getHeaders().get(RETRY_COUNT_HEADER);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private long deliveryTag(Message message) {
        return message.getMessageProperties().getDeliveryTag();
    }
}
