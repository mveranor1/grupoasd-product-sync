package com.grupoasd.consumer.messaging;

import com.grupoasd.consumer.domain.entity.ProductoDestino;
import com.grupoasd.consumer.dto.message.ProductoEventMessage;
import com.grupoasd.consumer.service.ProductoSincronizacionService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Consumidor JMS en el Componente 2 que escucha la cola de productos de ActiveMQ.
 */
@Component
public class ProductoConsumerListener {

    private static final Logger log = LoggerFactory.getLogger(ProductoConsumerListener.class);

    private final ProductoSincronizacionService productoSincronizacionService;
    private final Validator validator;

    public ProductoConsumerListener(ProductoSincronizacionService productoSincronizacionService,
                                    Validator validator) {
        this.productoSincronizacionService = productoSincronizacionService;
        this.validator = validator;
    }

    @JmsListener(
            destination = "${app.messaging.queues.producto-registro}",
            containerFactory = "jmsListenerContainerFactory"
    )
    public void consumirMensajeDeProducto(ProductoEventMessage mensaje) {
        log.info("[COMPONENTE 2][MENSAJE RECIBIDO] Mensaje consumido desde ActiveMQ con la información del producto: {}",
                mensaje);

        validarEstructuraYDatosDelMensaje(mensaje);

        ProductoDestino resultado = productoSincronizacionService.sincronizarProducto(mensaje);

        log.info("[COMPONENTE 2][PROCESAMIENTO EXITOSO - ACK] Producto codigo='{}' procesado y almacenado automáticamente en grupoasd_prod (idDestino={}). Mensaje confirmado en ActiveMQ.",
                resultado.getCodigo(), resultado.getId());
    }

    private void validarEstructuraYDatosDelMensaje(ProductoEventMessage mensaje) {
        if (mensaje == null) {
            log.error("[COMPONENTE 2][ERROR VALIDACION] Se recibió un mensaje nulo desde la cola de ActiveMQ.");
            throw new IllegalArgumentException("El mensaje de producto recibido no puede ser nulo");
        }

        Set<ConstraintViolation<ProductoEventMessage>> violations = validator.validate(mensaje);
        if (!violations.isEmpty()) {
            String errores = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining("; "));
            log.error("[COMPONENTE 2][ERROR VALIDACION] El mensaje para el producto codigo='{}' es inválido: {}. Se cancela persistencia y se envía a reintento/DLQ.",
                    mensaje.getCodigo(), errores);
            throw new IllegalArgumentException("Datos de producto inválidos en el mensaje JMS: " + errores);
        }
    }
}
