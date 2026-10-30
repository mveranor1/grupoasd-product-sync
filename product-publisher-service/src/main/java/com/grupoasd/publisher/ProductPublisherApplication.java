package com.grupoasd.publisher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Aplicación principal del Componente 1:
 * Servicio de registro de productos, Job programado de publicación en ActiveMQ
 * y receptor de confirmaciones de persistencia en destino (notificado = true).
 */
@SpringBootApplication
@EnableScheduling
public class ProductPublisherApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductPublisherApplication.class, args);
    }
}
