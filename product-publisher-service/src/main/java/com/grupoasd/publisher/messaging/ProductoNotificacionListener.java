package com.grupoasd.publisher.messaging;

import com.grupoasd.publisher.dto.message.ProductoNotificacionMessage;
import com.grupoasd.publisher.service.ProductoService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Escucha la cola de confirmaciones enviada por el Componente 2 y cambia el campo
 * 'notificado' a true en la tabla producto del esquema grupoasd_dev.
 */
@Component
public class ProductoNotificacionListener {

    private static final Logger log = LoggerFactory.getLogger(ProductoNotificacionListener.class);

    private final ProductoService productoService;
    private final Validator validator;

    public ProductoNotificacionListener(ProductoService productoService, Validator validator) {
        this.productoService = productoService;
        this.validator = validator;
    }

    @JmsListener(
            destination = "${app.messaging.queues.producto-notificacion}",
            containerFactory = "jmsListenerContainerFactory"
    )
    public void recibirNotificacionDePersistencia(ProductoNotificacionMessage notificacion) {
        log.info("Notificación recibida desde Componente 2 en cola de confirmación: {}", notificacion);
        validarMensajeNotificacion(notificacion);
        productoService.procesarConfirmacionDePersistencia(notificacion);
    }

    private void validarMensajeNotificacion(ProductoNotificacionMessage notificacion) {
        if (notificacion == null) {
            throw new IllegalArgumentException("El mensaje de notificación recibido es nulo");
        }
        Set<ConstraintViolation<ProductoNotificacionMessage>> violations = validator.validate(notificacion);
        if (!violations.isEmpty()) {
            String detalleErrores = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining(", "));
            log.error("Mensaje de notificación inválido: {}", detalleErrores);
            throw new IllegalArgumentException("Mensaje de notificación inválido: " + detalleErrores);
        }
    }
}
