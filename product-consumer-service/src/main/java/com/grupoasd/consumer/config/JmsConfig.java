package com.grupoasd.consumer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.grupoasd.consumer.dto.message.ProductoEventMessage;
import com.grupoasd.consumer.dto.message.ProductoNotificacionMessage;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Session;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.RedeliveryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.MessageType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuración de mensajería JMS en el Componente 2 con política de reintentos y Dead Letter Queue (DLQ).
 */
@Configuration
@EnableJms
@SuppressWarnings("all")
public class JmsConfig {

    private static final Logger log = LoggerFactory.getLogger(JmsConfig.class);

    public static final String TYPE_ID_PRODUCTO_EVENT = "ProductoEventMessage";
    public static final String TYPE_ID_PRODUCTO_NOTIFICACION = "ProductoNotificacionMessage";

    @Value("${spring.activemq.broker-url}")
    private String brokerUrl;

    @Value("${spring.activemq.user}")
    private String brokerUser;

    @Value("${spring.activemq.password}")
    private String brokerPassword;

    @Value("${app.messaging.redelivery.maximum-redeliveries:3}")
    private int maximumRedeliveries;

    @Value("${app.messaging.redelivery.initial-redelivery-delay-ms:2000}")
    private long initialRedeliveryDelayMs;

    @Value("${app.messaging.redelivery.backoff-multiplier:2.0}")
    private double backoffMultiplier;

    @Value("${app.messaging.redelivery.use-exponential-backoff:true}")
    private boolean useExponentialBackoff;

    @Bean
    public RedeliveryPolicy activeMqRedeliveryPolicy() {
        RedeliveryPolicy policy = new RedeliveryPolicy();
        policy.setMaximumRedeliveries(maximumRedeliveries);
        policy.setInitialRedeliveryDelay(initialRedeliveryDelayMs);
        policy.setRedeliveryDelay(initialRedeliveryDelayMs);
        policy.setBackOffMultiplier(backoffMultiplier);
        policy.setUseExponentialBackOff(useExponentialBackoff);
        return policy;
    }

    @Bean
    public ActiveMQConnectionFactory connectionFactory(RedeliveryPolicy activeMqRedeliveryPolicy) {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUser, brokerPassword, brokerUrl);
        factory.setTrustedPackages(List.of("com.grupoasd", "java.util", "java.lang", "java.math", "java.time"));
        factory.setRedeliveryPolicy(activeMqRedeliveryPolicy);
        return factory;
    }

    @Bean
    public ObjectMapper jmsObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean
    public MappingJackson2MessageConverter jacksonJmsMessageConverter(ObjectMapper jmsObjectMapper) {
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(jmsObjectMapper);
        converter.setTargetType(MessageType.TEXT);
        converter.setTypeIdPropertyName("_typeId");

        Map<String, Class<?>> typeIdMappings = new HashMap<>();
        typeIdMappings.put(TYPE_ID_PRODUCTO_EVENT, ProductoEventMessage.class);
        typeIdMappings.put(TYPE_ID_PRODUCTO_NOTIFICACION, ProductoNotificacionMessage.class);
        converter.setTypeIdMappings(typeIdMappings);

        return converter;
    }

    @Bean
    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jacksonJmsMessageConverter) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jacksonJmsMessageConverter);
        factory.setSessionTransacted(true);
        factory.setSessionAcknowledgeMode(Session.SESSION_TRANSACTED);
        factory.setErrorHandler(t ->
                log.error("Fallo durante el procesamiento del mensaje en Componente 2 (se aplicará reintento / DLQ): {}",
                        t.getMessage())
        );
        return factory;
    }
}
