package com.grupoasd.consumer.messaging;

import com.grupoasd.consumer.dto.message.ProductoNotificacionMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

/**
 * Publicador JMS en el Componente 2 que envía la confirmación al Componente 1
 * para que actualice notificado = true en grupoasd_dev.
 */
@Component
public class NotificacionJmsPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificacionJmsPublisher.class);

    private final JmsTemplate jmsTemplate;

    @Value("${app.messaging.queues.producto-notificacion}")
    private String colaProductoNotificacion;

    public NotificacionJmsPublisher(JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

    public void publicarConfirmacionDePersistencia(ProductoNotificacionMessage notificacion) {
        jmsTemplate.convertAndSend(colaProductoNotificacion, notificacion, jmsMessage -> {
            jmsMessage.setStringProperty("codigoProducto", notificacion.getCodigo());
            return jmsMessage;
        });

        log.info("Notificación enviada a cola '{}' para que el Componente 1 actualice notificado=true del producto codigo='{}'",
                colaProductoNotificacion, notificacion.getCodigo());
    }
}
