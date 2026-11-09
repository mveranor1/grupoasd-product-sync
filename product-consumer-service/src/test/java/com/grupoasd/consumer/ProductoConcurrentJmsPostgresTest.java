package com.grupoasd.consumer;

import com.grupoasd.consumer.domain.entity.ProductoDestino;
import com.grupoasd.consumer.dto.message.ProductoEventMessage;
import com.grupoasd.consumer.dto.message.ProductoNotificacionMessage;
import com.grupoasd.consumer.repository.ProductoDestinoRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * Dos consumidores JMS REALES compiten por el mismo codigo contra PostgreSQL.
 * El segundo mensaje debe sufrir rollback y ActiveMQ debe reentregarlo.
 * No usa las colas de la aplicación ni la base de datos de producción.
 */
@SpringBootTest(properties = {
        "app.messaging.queues.producto-registro=test.concurrent.jms.registro.queue",
        "app.messaging.queues.producto-notificacion=test.concurrent.jms.notificacion.queue",
        "app.messaging.listener.concurrency=2-2",
        "app.messaging.listener.queue-prefetch=1",
        "app.messaging.redelivery.maximum-redeliveries=3",
        "app.messaging.redelivery.initial-redelivery-delay-ms=300",
        "app.messaging.redelivery.use-exponential-backoff=false"
})
@ActiveProfiles("test")
class ProductoConcurrentJmsPostgresTest {

    private static final String CODIGO = "PROD-JMS-CONCURRENT-REAL-01";
    private static final String TEST_DB_URL = System.getenv("CONCURRENCY_TEST_DB_URL");
    private static final String REGISTRO = "test.concurrent.jms.registro.queue";
    private static final String NOTIFICACION = "test.concurrent.jms.notificacion.queue";

    @DynamicPropertySource
    static void configurarPostgres(DynamicPropertyRegistry registry) {
        if (TEST_DB_URL != null && !TEST_DB_URL.isBlank()) {
            if (!TEST_DB_URL.contains("grupoasd_test_db")) {
                throw new IllegalArgumentException(
                        "La prueba solo puede ejecutarse contra grupoasd_test_db");
            }
            registry.add("spring.datasource.url", () -> TEST_DB_URL);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username",
                    () -> System.getenv().getOrDefault("CONCURRENCY_TEST_DB_USER", "grupoasd_user"));
            registry.add("spring.datasource.password",
                    () -> System.getenv().getOrDefault("CONCURRENCY_TEST_DB_PASSWORD", "grupoasd_pass"));
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
            registry.add("spring.jpa.properties.hibernate.hbm2ddl.create_namespaces", () -> "true");
            registry.add("spring.jpa.properties.hibernate.default_schema", () -> "grupoasd_prod");
            registry.add("spring.activemq.broker-url", () -> "vm://localhost?broker.persistent=false");
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JmsTemplate jmsTemplate;

    @MockitoSpyBean
    private ProductoDestinoRepository repositorio;

    @BeforeEach
    void preparar() {
        assumeTrue(TEST_DB_URL != null && !TEST_DB_URL.isBlank(),
                "Se requiere PostgreSQL de prueba con CONCURRENCY_TEST_DB_URL");
        assertThat(TEST_DB_URL).contains("grupoasd_test_db");
        jdbcTemplate.update("DELETE FROM grupoasd_prod.producto WHERE codigo = ?", CODIGO);
        jmsTemplate.setReceiveTimeout(150);
        // La cola del test no se usa en otros escenarios.
        while (jmsTemplate.receive(NOTIFICACION) != null) {
            // limpiar notificaciones antiguas
        }
    }

    @Test
    void dosConsumidoresJmsReentreganTrasColisionYGuardanSoloUnProducto() throws Exception {
        CountDownLatch consultasIniciales = new CountDownLatch(2);
        AtomicInteger consultas = new AtomicInteger();

        doAnswer(invocation -> {
            Optional<ProductoDestino> encontrado = entityManager.createQuery(
                            "SELECT p FROM ProductoDestino p WHERE UPPER(p.codigo) = :codigo",
                            ProductoDestino.class)
                    .setParameter("codigo", CODIGO)
                    .setMaxResults(1)
                    .getResultList()
                    .stream()
                    .findFirst();

            int numero = consultas.incrementAndGet();
            if (numero <= 2 && encontrado.isEmpty()) {
                consultasIniciales.countDown();
                if (!consultasIniciales.await(12, TimeUnit.SECONDS)) {
                    throw new AssertionError("Dos consumidores JMS no consultaron simultaneamente");
                }
            }
            return encontrado;
        }).when(repositorio).findByCodigoIgnoreCase(eq(CODIGO));

        ProductoEventMessage mensaje = new ProductoEventMessage(
                992L, CODIGO, "Producto JMS concurrente",
                "Prueba de colision y reentrega con ActiveMQ",
                new BigDecimal("100000.00"), LocalDateTime.now(), LocalDateTime.now());

        jmsTemplate.convertAndSend(REGISTRO, mensaje);
        jmsTemplate.convertAndSend(REGISTRO, mensaje);

        // La primera confirmacion llega tras el commit ganador. La segunda
        // solo puede llegar tras la reentrega exitosa del perdedor.
        jmsTemplate.setReceiveTimeout(20000);
        Object primera = jmsTemplate.receiveAndConvert(NOTIFICACION);
        Object segunda = jmsTemplate.receiveAndConvert(NOTIFICACION);

        assertThat(primera).isInstanceOf(ProductoNotificacionMessage.class);
        assertThat(segunda).isInstanceOf(ProductoNotificacionMessage.class);
        ProductoNotificacionMessage n1 = (ProductoNotificacionMessage) primera;
        ProductoNotificacionMessage n2 = (ProductoNotificacionMessage) segunda;
        assertThat(n1.isPersistido()).isTrue();
        assertThat(n2.isPersistido()).isTrue();
        assertThat(n1.getCodigo()).isEqualTo(CODIGO);
        assertThat(n2.getCodigo()).isEqualTo(CODIGO);
        assertThat(n1.getIdDestino()).isEqualTo(n2.getIdDestino());
        assertThat(consultasIniciales.getCount()).isZero();
        assertThat(consultas.get()).isGreaterThanOrEqualTo(3);

        Long filas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM grupoasd_prod.producto WHERE codigo = ?",
                Long.class, CODIGO);
        assertThat(filas).isEqualTo(1L);
    }
}
