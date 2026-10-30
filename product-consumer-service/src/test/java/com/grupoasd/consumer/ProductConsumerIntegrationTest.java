package com.grupoasd.consumer;

import com.grupoasd.consumer.domain.entity.ProductoDestino;
import com.grupoasd.consumer.dto.message.ProductoEventMessage;
import com.grupoasd.consumer.dto.message.ProductoNotificacionMessage;
import com.grupoasd.consumer.messaging.NotificacionJmsPublisher;
import com.grupoasd.consumer.messaging.ProductoConsumerListener;
import com.grupoasd.consumer.repository.ProductoDestinoRepository;
import jakarta.jms.Message;
import org.apache.activemq.RedeliveryPolicy;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Pruebas de integración reales del Componente 2 (product-consumer-service)
 * utilizando @SpringBootTest, PostgreSQL real (base de datos aislada grupoasd_test_db, esquema grupoasd_prod)
 * y broker ActiveMQ real con envío a cola JMS, @JmsListener, RedeliveryPolicy, ActiveMQ.DLQ y Awaitility.
 */
@SpringBootTest
@ActiveProfiles("test")
@SuppressWarnings("all")
class ProductConsumerIntegrationTest {

    @DynamicPropertySource
    static void configurarBaseDeDatosAisladaDePruebas(DynamicPropertyRegistry registry) {
        if (esPostgreSqlDisponible()) {
            registry.add("spring.datasource.url",
                    () -> "jdbc:postgresql://localhost:5432/grupoasd_test_db?currentSchema=grupoasd_prod");
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> "grupoasd_user");
            registry.add("spring.datasource.password", () -> "grupoasd_pass");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
            registry.add("spring.jpa.properties.hibernate.default_schema", () -> "grupoasd_prod");
        }
    }

    private static boolean esPostgreSqlDisponible() {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/grupoasd_test_db", "grupoasd_user", "grupoasd_pass");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS grupoasd_prod");
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    @Autowired
    private ProductoConsumerListener productoConsumerListener;

    @MockitoSpyBean
    private ProductoDestinoRepository productoDestinoRepository;

    @MockitoSpyBean
    private NotificacionJmsPublisher notificacionJmsPublisher;

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedeliveryPolicy activeMqRedeliveryPolicy;

    @Value("${app.messaging.queues.producto-registro}")
    private String colaRegistro;

    @Value("${app.messaging.queues.producto-notificacion}")
    private String colaNotificacion;

    @BeforeEach
    void setUp() {
        Mockito.reset(productoDestinoRepository, notificacionJmsPublisher);
        productoDestinoRepository.deleteAll();
        purgarCola(colaNotificacion);
        purgarCola("ActiveMQ.DLQ");
    }

    private void purgarCola(String nombreCola) {
        jmsTemplate.setReceiveTimeout(100);
        while (jmsTemplate.receive(nombreCola) != null) {
            // Limpia mensajes remanentes en la cola de prueba
        }
    }

    @Test
    @DisplayName("1. Debe recibir un mensaje válido desde ActiveMQ, persistirlo en grupoasd_prod y publicar la confirmación en la cola de retorno")
    void debeConsumirDesdeColaJmsPersistirYPublicarConfirmacion() {
        ProductoEventMessage mensaje = new ProductoEventMessage(
                10L,
                "PROD-SYNC-001",
                "Switch Administrable 24 Puertos",
                "Switch Gigabit Layer 3 para Centro de Datos",
                new BigDecimal("3200000.00"),
                LocalDateTime.now().minusMinutes(2),
                LocalDateTime.now()
        );

        // Enviar mensaje real a la cola de ActiveMQ para que @JmsListener lo procese
        jmsTemplate.convertAndSend(colaRegistro, mensaje);

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    var guardadoOpt = productoDestinoRepository.findByCodigoIgnoreCase("PROD-SYNC-001");
                    assertThat(guardadoOpt).isPresent();
                    ProductoDestino guardado = guardadoOpt.get();
                    assertThat(guardado.getId()).isNotNull();
                    assertThat(guardado.getIdOrigen()).isEqualTo(10L);
                    assertThat(guardado.getNombre()).isEqualTo("Switch Administrable 24 Puertos");
                    assertThat(guardado.getPrecio()).isEqualByComparingTo("3200000.00");
                });

        // Verificar que la confirmación fue publicada realmente en la cola de notificación de ActiveMQ
        jmsTemplate.setReceiveTimeout(3000);
        Object confirmacionObj = jmsTemplate.receiveAndConvert(colaNotificacion);
        assertThat(confirmacionObj).isInstanceOf(ProductoNotificacionMessage.class);

        ProductoNotificacionMessage notificacionEnviada = (ProductoNotificacionMessage) confirmacionObj;
        assertThat(notificacionEnviada.getCodigo()).isEqualTo("PROD-SYNC-001");
        assertThat(notificacionEnviada.getIdOrigen()).isEqualTo(10L);
        assertThat(notificacionEnviada.isPersistido()).isTrue();
    }

    @Test
    @DisplayName("2. Debe prevenir registros duplicados en grupoasd_prod si ActiveMQ entrega el mismo mensaje múltiples veces")
    void debePrevenirDuplicadosAnteRecepcionRepetidaDeMensajesEnCola() {
        ProductoEventMessage mensaje = new ProductoEventMessage(
                25L,
                "PROD-IDEMP-002",
                "Disco SSD NVMe 2TB",
                "Unidad de estado sólido PCIe 4.0",
                new BigDecimal("850000.00"),
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        jmsTemplate.convertAndSend(colaRegistro, mensaje);
        jmsTemplate.convertAndSend(colaRegistro, mensaje);

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    verify(notificacionJmsPublisher, times(2))
                            .publicarConfirmacionDePersistencia(any(ProductoNotificacionMessage.class));
                    assertThat(productoDestinoRepository.count()).isEqualTo(1L);
                });
    }

    @Test
    @DisplayName("3. Debe rechazar mensajes inválidos, aplicar reintentos en ActiveMQ y enviar el mensaje fallido a ActiveMQ.DLQ")
    void debeRechazarMensajeInvalidoYEnviarADlqTrasReintentos() {
        ProductoEventMessage mensajeInvalido = new ProductoEventMessage(
                99L,
                "",
                "X",
                "Mal",
                new BigDecimal("-50.00"),
                null,
                null
        );

        jmsTemplate.convertAndSend(colaRegistro, mensajeInvalido);

        // Esperar a que ActiveMQ agote los reintentos configurados y mueva el mensaje a ActiveMQ.DLQ
        jmsTemplate.setReceiveTimeout(4000);
        Message mensajeEnDlq = jmsTemplate.receive("ActiveMQ.DLQ");
        assertThat(mensajeEnDlq).isNotNull();

        assertThat(productoDestinoRepository.count()).isZero();
        verifyNoInteractions(notificacionJmsPublisher);
    }

    @Test
    @DisplayName("4. Ante fallo temporal de persistencia, no debe confirmar el mensaje en el intento fallido y debe recuperarse en el reintento JMS")
    void debeGestionarErrorDePersistenciaYRecuperarseEnReintentoJms() {
        AtomicBoolean primerIntentoFallido = new AtomicBoolean(true);

        doAnswer(invocation -> {
            if (primerIntentoFallido.compareAndSet(true, false)) {
                Mockito.reset(productoDestinoRepository);
                throw new DataAccessResourceFailureException("Fallo temporal simulado de escritura en PostgreSQL");
            }
            return null;
        }).when(productoDestinoRepository).save(any(ProductoDestino.class));

        ProductoEventMessage mensaje = new ProductoEventMessage(
                70L,
                "PROD-RETRY-DB-01",
                "Servidor Blade",
                "Nodo de cómputo empresarial redundante",
                new BigDecimal("19500000.00"),
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now()
        );

        jmsTemplate.convertAndSend(colaRegistro, mensaje);

        Awaitility.await()
                .atMost(Duration.ofSeconds(6))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    var persistidoOpt = productoDestinoRepository.findByCodigoIgnoreCase("PROD-RETRY-DB-01");
                    assertThat(persistidoOpt).isPresent();
                    assertThat(persistidoOpt.get().getId()).isNotNull();
                });

        // Solo se envió 1 confirmación (correspondiente al segundo intento exitoso, nunca al primero que falló)
        verify(notificacionJmsPublisher, times(1))
                .publicarConfirmacionDePersistencia(any(ProductoNotificacionMessage.class));
    }

    @Test
    @DisplayName("5. Debe contar con política de reintentos configurada en ActiveMQConnectionFactory")
    void debeVerificarPoliticaDeReintentosAnteFallosReiterados() {
        assertThat(activeMqRedeliveryPolicy).isNotNull();
        assertThat(activeMqRedeliveryPolicy.getMaximumRedeliveries()).isGreaterThanOrEqualTo(2);
        assertThat(activeMqRedeliveryPolicy.getInitialRedeliveryDelay()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("6. Debe verificar la generación del esquema grupoasd_prod y la tabla producto en destino")
    void debeGenerarEsquemaGrupoAsdProdYTablaProducto() {
        Integer countTabla = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_SCHEMA) = 'grupoasd_prod' AND LOWER(TABLE_NAME) = 'producto'",
                Integer.class
        );
        assertThat(countTabla).isNotNull().isGreaterThan(0);

        Integer countColumnas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_SCHEMA) = 'grupoasd_prod' AND LOWER(TABLE_NAME) = 'producto' AND LOWER(COLUMN_NAME) IN ('id_origen', 'codigo', 'nombre', 'precio', 'fecha_sincronizacion')",
                Integer.class
        );
        assertThat(countColumnas).isEqualTo(5);
    }
}
