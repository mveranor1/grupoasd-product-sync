package com.grupoasd.publisher.config;

import org.apache.activemq.broker.BrokerService;
import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.sql.SQLException;

/**
 * Configuración exclusiva para el modo local sin Docker.
 * Levanta automáticamente el servidor de Base de Datos y el broker de ActiveMQ en el puerto 61616.
 */
@Configuration
@Profile("local")
public class LocalEmbeddedServersConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddedServersConfig.class);

    @Bean
    public static BeanFactoryPostProcessor inicializarServidoresLocales() {
        return (ConfigurableListableBeanFactory beanFactory) -> {
            iniciarServidorBaseDeDatosTcp();
            iniciarBrokerActiveMqTcp();
        };
    }

    private static void iniciarServidorBaseDeDatosTcp() {
        try {
            Server server = Server.createTcpServer("-tcp", "-tcpAllowOthers", "-tcpPort", "9092", "-ifNotExists").start();
            log.info("SERVIDOR BD LOCAL INICIADO EN PUERTO TCP {} (Consola H2: http://localhost:8080/h2-console)", server.getPort());
        } catch (SQLException e) {
            log.info("El servidor de Base de Datos TCP ya se encuentra en ejecución.");
        }
    }

    private static void iniciarBrokerActiveMqTcp() {
        try {
            BrokerService broker = new BrokerService();
            broker.setBrokerName("grupoasd-local-broker");
            broker.setPersistent(false);
            broker.setUseJmx(false);
            broker.addConnector("tcp://localhost:61616");
            broker.start();
            log.info("BROKER ACTIVEMQ LOCAL INICIADO EN: tcp://localhost:61616");
        } catch (Exception e) {
            log.info("El broker de ActiveMQ en tcp://localhost:61616 ya se encuentra en ejecución.");
        }
    }
}
