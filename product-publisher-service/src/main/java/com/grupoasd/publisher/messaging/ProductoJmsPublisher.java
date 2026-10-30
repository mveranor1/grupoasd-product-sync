package com.grupoasd.publisher.messaging;

import com.grupoasd.publisher.dto.message.ProductoEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica en la cola de ActiveMQ un mensaje por cada producto con toda la información del producto.
 */
@Component
public class ProductoJmsPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProductoJmsPublisher.class);

    private final JmsTemplate jmsTemplate;

    @Value("${app.messaging.queues.producto-registro}")
    private String colaProductoRegistro;

    public ProductoJmsPublisher(JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

    public void publicarProducto(ProductoEventMessage mensaje) {
        jmsTemplate.convertAndSend(colaProductoRegistro, mensaje, jmsMessage -> {
            jmsMessage.setStringProperty("codigoProducto", mensaje.getCodigo());
            jmsMessage.setLongProperty("idOrigen", mensaje.getIdOrigen());
            return jmsMessage;
        });

        log.info("[COMPONENTE 1][MENSAJE ENVIADO] Cola='{}' | idOrigen={} | codigo='{}' | contenido={}",
                colaProductoRegistro, mensaje.getIdOrigen(), mensaje.getCodigo(), mensaje);
    }
}
