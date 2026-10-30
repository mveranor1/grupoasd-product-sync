package com.grupoasd.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grupoasd.publisher.domain.entity.Producto;
import com.grupoasd.publisher.dto.ProductoRequestDto;
import com.grupoasd.publisher.dto.message.ProductoEventMessage;
import com.grupoasd.publisher.dto.message.ProductoNotificacionMessage;
import com.grupoasd.publisher.mapper.ProductoMapper;
import com.grupoasd.publisher.messaging.ProductoJmsPublisher;
import com.grupoasd.publisher.repository.ProductoRepository;
import com.grupoasd.publisher.scheduler.ProductoPublicacionJob;
import com.grupoasd.publisher.service.ProductoService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FASE 4 — Prueba automatizada del flujo completo de extremo a extremo entre ambos esquemas
 * (grupoasd_dev.producto -> ActiveMQ -> grupoasd_prod.producto -> ActiveMQ -> grupoasd_dev.producto):
 * 1. API REST recibe un producto.
 * 2. Se guarda en grupoasd_dev.producto con publicado=false y notificado=false.
 * 3. El Job identifica el producto pendiente y lo publica en ActiveMQ (publicado=true).
 * 4. El consumidor recibe el mensaje de ActiveMQ, verifica idempotencia y persiste en grupoasd_prod.producto.
 * 5. El consumidor envía la confirmación por ActiveMQ.
 * 6. El publicador recibe la confirmación desde ActiveMQ y actualiza notificado=true en grupoasd_dev.producto.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("all")
class FlujoCompletoSincronizacionE2ETest {

    @DynamicPropertySource
    static void configurarBaseDeDatosAisladaDePruebas(DynamicPropertyRegistry registry) {
        if (esPostgreSqlDisponible()) {
            registry.add("spring.datasource.url",
                    () -> "jdbc:postgresql://localhost:5432/grupoasd_test_db?currentSchema=grupoasd_dev");
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> "grupoasd_user");
            registry.add("spring.datasource.password", () -> "grupoasd_pass");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
            registry.add("spring.jpa.properties.hibernate.default_schema", () -> "grupoasd_dev");
        }
    }

    private static boolean esPostgreSqlDisponible() {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/grupoasd_test_db", "grupoasd_user", "grupoasd_pass");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS grupoasd_dev");
            stmt.execute("CREATE SCHEMA IF NOT EXISTS grupoasd_prod");
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductoRepository productoRepository;

    @Autowired
    private ProductoService productoService;

    @Autowired
    private ProductoMapper productoMapper;

    @Autowired
    private ProductoJmsPublisher productoJmsPublisher;

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${app.messaging.queues.producto-registro}")
    private String colaRegistro;

    @Value("${app.messaging.queues.producto-notificacion}")
    private String colaNotificacion;

    private ProductoPublicacionJob productoPublicacionJob;

    @BeforeEach
    void setUp() {
        productoRepository.deleteAll();
        inicializarEsquemaDestinoParaPruebaE2E();
        jdbcTemplate.execute("DELETE FROM grupoasd_prod.producto");
        purgarCola(colaRegistro);
        purgarCola(colaNotificacion);

        productoPublicacionJob = new ProductoPublicacionJob(
                productoService,
                productoJmsPublisher,
                productoMapper
        );
        ReflectionTestUtils.setField(productoPublicacionJob, "batchSize", 50);
    }

    private void inicializarEsquemaDestinoParaPruebaE2E() {
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS grupoasd_prod");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS grupoasd_prod.producto (
                    id BIGSERIAL PRIMARY KEY,
                    id_origen BIGINT,
                    codigo VARCHAR(60) NOT NULL UNIQUE,
                    nombre VARCHAR(150) NOT NULL,
                    descripcion VARCHAR(500) NOT NULL,
                    precio NUMERIC(19, 2) NOT NULL,
                    fecha_creacion TIMESTAMP NOT NULL,
                    fecha_sincronizacion TIMESTAMP NOT NULL
                )
                """);
    }

    private void purgarCola(String nombreCola) {
        jmsTemplate.setReceiveTimeout(100);
        while (jmsTemplate.receive(nombreCola) != null) {
            // Limpia cola
        }
    }

    /**
     * Procesa un mensaje real extraído de la cola de registro de ActiveMQ aplicando la misma
     * lógica idempotente del Componente 2 sobre grupoasd_prod.producto y publicando la confirmación
     * en la cola de notificación de ActiveMQ.
     */
    private void procesarMensajeEnConsumidorDestino(ProductoEventMessage evento) {
        String codigoNormalizado = evento.getCodigo().trim().toUpperCase(Locale.ROOT);
        Integer existentes = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM grupoasd_prod.producto WHERE UPPER(codigo) = ?",
                Integer.class,
                codigoNormalizado
        );

        LocalDateTime ahora = LocalDateTime.now();
        if (existentes == null || existentes == 0) {
            jdbcTemplate.update("""
                    INSERT INTO grupoasd_prod.producto
                        (id_origen, codigo, nombre, descripcion, precio, fecha_creacion, fecha_sincronizacion)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    evento.getIdOrigen(),
                    codigoNormalizado,
                    evento.getNombre().trim(),
                    evento.getDescripcion().trim(),
                    evento.getPrecio(),
                    Timestamp.valueOf(evento.getFechaCreacion() != null ? evento.getFechaCreacion() : ahora),
                    Timestamp.valueOf(ahora)
            );
        }

        Long idDestino = jdbcTemplate.queryForObject(
                "SELECT id FROM grupoasd_prod.producto WHERE UPPER(codigo) = ?",
                Long.class,
                codigoNormalizado
        );

        ProductoNotificacionMessage confirmacion = new ProductoNotificacionMessage(
                evento.getIdOrigen(),
                idDestino,
                codigoNormalizado,
                true,
                ahora
        );

        jmsTemplate.convertAndSend(colaNotificacion, confirmacion);
    }

    @Test
    @DisplayName("E2E 1: Recorrido completo de los 10 pasos (REST -> grupoasd_dev -> Job -> ActiveMQ -> grupoasd_prod -> Confirmación -> notificado=true)")
    void debeCompletarFlujoDeSincronizacionDeExtremoAExtremo() throws Exception {
        // Paso 1: La API REST recibe un producto
        ProductoRequestDto request = new ProductoRequestDto(
                "PROD-E2E-001",
                "Servidor Hiperconvergente",
                "Nodo empresarial de virtualización y almacenamiento",
                new BigDecimal("38500000.00")
        );

        mockMvc.perform(post("/api/v1/productos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.codigo").value("PROD-E2E-001"))
                .andExpect(jsonPath("$.publicado").value(false))
                .andExpect(jsonPath("$.notificado").value(false));

        // Pasos 2 y 3: El producto se guarda en grupoasd_dev.producto con publicado=false y notificado=false
        Producto enOrigenInicial = productoRepository.findByCodigoIgnoreCase("PROD-E2E-001").orElseThrow();
        assertThat(enOrigenInicial.isPublicado()).isFalse();
        assertThat(enOrigenInicial.isNotificado()).isFalse();

        // Pasos 4 y 5: El Job identifica el producto pendiente y lo publica en ActiveMQ
        productoPublicacionJob.ejecutarPublicacionDeProductosPendientes();

        Producto enOrigenPublicado = productoRepository.findByCodigoIgnoreCase("PROD-E2E-001").orElseThrow();
        assertThat(enOrigenPublicado.isPublicado()).isTrue();
        assertThat(enOrigenPublicado.isNotificado()).isFalse();

        // Pasos 6, 7 y 8: El consumidor recibe el mensaje desde ActiveMQ, lo persiste en grupoasd_prod.producto y envía confirmación
        jmsTemplate.setReceiveTimeout(3000);
        Object mensajeJms = jmsTemplate.receiveAndConvert(colaRegistro);
        assertThat(mensajeJms).isInstanceOf(ProductoEventMessage.class);
        procesarMensajeEnConsumidorDestino((ProductoEventMessage) mensajeJms);

        // Pasos 9 y 10: El publicador recibe la confirmación por @JmsListener y termina con notificado=true
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    var enOrigenFinalOpt = productoRepository.findByCodigoIgnoreCase("PROD-E2E-001");
                    assertThat(enOrigenFinalOpt).isPresent();
                    Producto enOrigenFinal = enOrigenFinalOpt.get();
                    assertThat(enOrigenFinal.isPublicado()).isTrue();
                    assertThat(enOrigenFinal.isNotificado()).isTrue();
                    assertThat(enOrigenFinal.getFechaPublicacion()).isNotNull();
                    assertThat(enOrigenFinal.getFechaNotificacion()).isNotNull();
                });

        // Comprobar estado final en ambas bases de datos / esquemas (grupoasd_dev y grupoasd_prod)
        Map<String, Object> filaDestino = jdbcTemplate.queryForMap(
                "SELECT id, id_origen, codigo, nombre, precio FROM grupoasd_prod.producto WHERE codigo = 'PROD-E2E-001'"
        );
        assertThat(filaDestino.get("codigo")).isEqualTo("PROD-E2E-001");
        assertThat(((Number) filaDestino.get("id_origen")).longValue()).isEqualTo(enOrigenInicial.getId());
        assertThat((BigDecimal) filaDestino.get("precio")).isEqualByComparingTo("38500000.00");
    }

    @Test
    @DisplayName("E2E 2: Recepción repetida en el flujo completo sin duplicar el producto en grupoasd_prod.producto")
    void debeEvitarDuplicadosEnFlujoCompletoAnteRecepcionRepetida() throws Exception {
        ProductoRequestDto request = new ProductoRequestDto(
                "PROD-E2E-DUP-02",
                " Unidad de Cinta LTO-9 ",
                "Librería de respaldo magnético empresarial",
                new BigDecimal("21000000.00")
        );

        mockMvc.perform(post("/api/v1/productos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        productoPublicacionJob.ejecutarPublicacionDeProductosPendientes();

        jmsTemplate.setReceiveTimeout(3000);
        ProductoEventMessage evento = (ProductoEventMessage) jmsTemplate.receiveAndConvert(colaRegistro);
        assertThat(evento).isNotNull();

        // Procesar el mismo mensaje dos veces en destino (simulando entrega repetida)
        procesarMensajeEnConsumidorDestino(evento);
        procesarMensajeEnConsumidorDestino(evento);

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    var enOrigenOpt = productoRepository.findByCodigoIgnoreCase("PROD-E2E-DUP-02");
                    assertThat(enOrigenOpt).isPresent();
                    Producto enOrigen = enOrigenOpt.get();
                    assertThat(enOrigen.isPublicado()).isTrue();
                    assertThat(enOrigen.isNotificado()).isTrue();
                });

        Integer totalEnDestino = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM grupoasd_prod.producto WHERE codigo = 'PROD-E2E-DUP-02'",
                Integer.class
        );
        assertThat(totalEnDestino).isEqualTo(1);
    }

    @Test
    @DisplayName("E2E 3: Escenario de fallo recuperable por pérdida temporal de confirmación y reenvío automático del Job")
    void debeRecuperarseDeFalloTemporalYCompletarSincronizacion() throws Exception {
        ProductoRequestDto request = new ProductoRequestDto(
                "PROD-E2E-REC-03",
                "Analizador de Red",
                "Equipo de diagnóstico de fibra óptica",
                new BigDecimal("7400000.00")
        );

        mockMvc.perform(post("/api/v1/productos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Ciclo 1: El Job publica el mensaje, pero simulamos un fallo temporal en el consumidor antes de persistir
        productoPublicacionJob.ejecutarPublicacionDeProductosPendientes();
        jmsTemplate.setReceiveTimeout(3000);
        ProductoEventMessage primerIntento = (ProductoEventMessage) jmsTemplate.receiveAndConvert(colaRegistro);
        assertThat(primerIntento).isNotNull();

        AtomicBoolean falloTemporalActivo = new AtomicBoolean(true);
        if (falloTemporalActivo.get()) {
            // El primer intento falla antes de guardar en grupoasd_prod: no hay confirmación y notificado sigue en false
            Producto sinConfirmar = productoRepository.findByCodigoIgnoreCase("PROD-E2E-REC-03").orElseThrow();
            assertThat(sinConfirmar.isPublicado()).isTrue();
            assertThat(sinConfirmar.isNotificado()).isFalse();

            // Ajustar fechaPublicacion para que supere la ventana de espera de reconciliación (20s)
            sinConfirmar.setFechaPublicacion(LocalDateTime.now().minusSeconds(25));
            productoRepository.save(sinConfirmar);
        }

        // Ciclo 2: El Job detecta que sigue con notificado=false y lo reenvía automáticamente a ActiveMQ
        falloTemporalActivo.set(false);
        productoPublicacionJob.ejecutarPublicacionDeProductosPendientes();

        ProductoEventMessage reenvio = (ProductoEventMessage) jmsTemplate.receiveAndConvert(colaRegistro);
        assertThat(reenvio).isNotNull();
        procesarMensajeEnConsumidorDestino(reenvio);

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    var recuperadoOpt = productoRepository.findByCodigoIgnoreCase("PROD-E2E-REC-03");
                    assertThat(recuperadoOpt).isPresent();
                    Producto recuperado = recuperadoOpt.get();
                    assertThat(recuperado.isPublicado()).isTrue();
                    assertThat(recuperado.isNotificado()).isTrue();
                    assertThat(recuperado.getIntentosPublicacion()).isGreaterThanOrEqualTo(2);
                });

        Integer countEnDestino = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM grupoasd_prod.producto WHERE codigo = 'PROD-E2E-REC-03'",
                Integer.class
        );
        assertThat(countEnDestino).isEqualTo(1);
    }
}
