package com.grupoasd.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Aplicación principal del Componente 2:
 * Consumidor de productos desde ActiveMQ, persistencia en grupoasd_prod
 * y envío de confirmación hacia el Componente 1 para marcar notificado = true.
 */
@SpringBootApplication
public class ProductConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductConsumerApplication.class, args);
    }
}
