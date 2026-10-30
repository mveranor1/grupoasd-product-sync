package com.grupoasd.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grupoasd.publisher.domain.entity.Producto;
import com.grupoasd.publisher.dto.ProductoRequestDto;
import com.grupoasd.publisher.dto.message.ProductoEventMessage;
import com.grupoasd.publisher.dto.message.ProductoNotificacionMessage;
import com.grupoasd.publisher.mapper.ProductoMapper;
import com.grupoasd.publisher.messaging.ProductoJmsPublisher;
import com.grupoasd.publisher.messaging.ProductoNotificacionListener;
import com.grupoasd.publisher.repository.ProductoRepository;
import com.grupoasd.publisher.scheduler.ProductoPublicacionJob;
import com.grupoasd.publisher.service.ProductoService;
import jakarta.jms.Message;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración reales del Componente 1 (product-publisher-service)
 * utilizando @SpringBootTest, MockMvc, PostgreSQL real (base de datos aislada grupoasd_test_db)
 * y broker ActiveMQ real con JmsTemplate, @JmsListener y Awaitility.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("all")
class ProductPublisherIntegrationTest {

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
    private ProductoNotificacionListener productoNotificacionListener;

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private ProductoJmsPublisher productoJmsPublisher;

    @Value("${app.messaging.queues.producto-registro}")
    private String colaRegistro;

    @Value("${app.messaging.queues.producto-notificacion}")
    private String colaNotificacion;

    private ProductoPublicacionJob productoPublicacionJob;

    @BeforeEach
    void setUp() {
        productoRepository.deleteAll();
        Mockito.reset(productoJmsPublisher);
        purgarCola(colaRegistro);
        productoPublicacionJob = new ProductoPublicacionJob(
                productoService,
                productoJmsPublisher,
                productoMapper
        );
        ReflectionTestUtils.setField(productoPublicacionJob, "batchSize", 50);
    }

    private void purgarCola(String nombreCola) {
        jmsTemplate.setReceiveTimeout(100);
        while (jmsTemplate.receive(nombreCola) != null) {
            // Limpia mensajes remanentes en la cola de prueba
        }
    }

    @Test
    @DisplayName("1. Debe registrar un producto vía API REST y persistirlo en grupoasd_dev con publicado=false y notificado=false")
    void debeRegistrarProductoExitosamentePorApiRest() throws Exception {
        ProductoRequestDto request = new ProductoRequestDto(
                "PROD-001",
                "Laptop Corporativa",
                "Laptop de alto rendimiento 32GB RAM",
                new BigDecimal("4500000.00")
        );

        mockMvc.perform(post("/api/v1/productos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.codigo").value("PROD-001"))
                .andExpect(jsonPath("$.publicado").value(false))
                .andExpect(jsonPath("$.notificado").value(false));

        Producto enBaseDeDatos = productoRepository.findByCodigoIgnoreCase("PROD-001").orElseThrow();
        assertThat(enBaseDeDatos.isPublicado()).isFalse();
        assertThat(enBaseDeDatos.isNotificado()).isFalse();
        assertThat(enBaseDeDatos.getFechaCreacion()).isNotNull();
    }

    @Test
    @DisplayName("2. Debe rechazar el registro de un producto con código duplicado retornando HTTP 409 Conflict")
    void debeRechazarProductoConCodigoDuplicado() throws Exception {
        ProductoRequestDto request = new ProductoRequestDto(
                "PROD-DUP-01",
                "Monitor 27 Pulgadas",
                "Monitor IPS 4K UHD Profesional",
                new BigDecimal("1200000.00")
        );

        mockMvc.perform(post("/api/v1/productos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/productos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());

        assertThat(productoRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("3. Debe rechazar peticiones con datos inválidos retornando HTTP 400 Bad Request")
    void debeRechazarProductoConInformacionInvalida() throws Exception {
        ProductoRequestDto requestInvalido = new ProductoRequestDto(
                "",
                "A",
                "Cort",
                new BigDecimal("-10.00")
        );

        mockMvc.perform(post("/api/v1/productos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestInvalido)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.codigo").exists())
                .andExpect(jsonPath("$.validationErrors.precio").exists());

        assertThat(productoRepository.count()).isZero();
    }

    @Test
    @DisplayName("4. Debe permitir consultar productos guardados por código, listar todos y listar pendientes")
    void debeConsultarProductosGuardadosPorApiRest() throws Exception {
        productoService.registrarProducto(new ProductoRequestDto(
                "PROD-GET-01",
                " Estación de Trabajo ",
                "Workstation Xeon 64GB RAM ECC",
                new BigDecimal("9800000.00")
        ));

        mockMvc.perform(get("/api/v1/productos/PROD-GET-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigo").value("PROD-GET-01"))
                .andExpect(jsonPath("$.publicado").value(false))
                .andExpect(jsonPath("$.notificado").value(false));

        mockMvc.perform(get("/api/v1/productos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/v1/productos/pendientes-publicacion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/v1/productos/CODIGO-INEXISTENTE"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("5. El Job debe identificar pendientes, publicar el mensaje real en ActiveMQ y actualizar publicado=true")
    void jobDebeIdentificarPendientesYPublicarMensajeRealEnActiveMQ() {
        productoService.registrarProducto(new ProductoRequestDto(
                "PROD-JOB-01",
                "Teclado Mecánico",
                "Teclado mecánico inalámbrico RGB",
                new BigDecimal("350000.00")
        ));

        productoPublicacionJob.ejecutarPublicacionDeProductosPendientes();

        verify(productoJmsPublisher, times(1)).publicarProducto(any(ProductoEventMessage.class));

        // Verificar que el mensaje realmente llegó a la cola de ActiveMQ
        jmsTemplate.setReceiveTimeout(3000);
        Object mensajeEnCola = jmsTemplate.receiveAndConvert(colaRegistro);
        assertThat(mensajeEnCola).isInstanceOf(ProductoEventMessage.class);
        ProductoEventMessage eventoRecibido = (ProductoEventMessage) mensajeEnCola;
        assertThat(eventoRecibido.getCodigo()).isEqualTo("PROD-JOB-01");
        assertThat(eventoRecibido.getPrecio()).isEqualByComparingTo("350000.00");

        Producto actualizado = productoRepository.findByCodigoIgnoreCase("PROD-JOB-01").orElseThrow();
        assertThat(actualizado.isPublicado()).isTrue();
        assertThat(actualizado.isNotificado()).isFalse();
        assertThat(actualizado.getFechaPublicacion()).isNotNull();
    }

    @Test
    @DisplayName("6. Ante fallo temporal de ActiveMQ, el Job debe mantener publicado=false y recuperarse en el siguiente ciclo")
    void jobDebeGestionarFalloTemporalDePublicacionParaReintento() {
        productoService.registrarProducto(new ProductoRequestDto(
                "PROD-RETRY-01",
                "Mouse Ergonómico",
                "Mouse vertical inalámbrico óptico",
                new BigDecimal("180000.00")
        ));

        doThrow(new RuntimeException("Broker ActiveMQ no disponible temporalmente"))
                .when(productoJmsPublisher).publicarProducto(any(ProductoEventMessage.class));

        productoPublicacionJob.ejecutarPublicacionDeProductosPendientes();

        Producto trasFallo = productoRepository.findByCodigoIgnoreCase("PROD-RETRY-01").orElseThrow();
        assertThat(trasFallo.isPublicado()).isFalse();
        assertThat(trasFallo.getIntentosPublicacion()).isEqualTo(1);
        assertThat(trasFallo.getUltimoErrorPublicacion()).contains("ActiveMQ no disponible");

        Mockito.reset(productoJmsPublisher);
        productoPublicacionJob.ejecutarPublicacionDeProductosPendientes();

        Producto trasRecuperacion = productoRepository.findByCodigoIgnoreCase("PROD-RETRY-01").orElseThrow();
        assertThat(trasRecuperacion.isPublicado()).isTrue();
        assertThat(trasRecuperacion.getIntentosPublicacion()).isEqualTo(2);
        assertThat(trasRecuperacion.getUltimoErrorPublicacion()).isNull();

        jmsTemplate.setReceiveTimeout(3000);
        Object mensajeRecuperado = jmsTemplate.receiveAndConvert(colaRegistro);
        assertThat(mensajeRecuperado).isInstanceOf(ProductoEventMessage.class);
    }

    @Test
    @DisplayName("7. Debe recibir por JMS el mensaje de confirmación en ActiveMQ y actualizar notificado=true en PostgreSQL")
    void debeActualizarNotificadoATrueCuandoComponente2ConfirmaPersistenciaPorJms() throws Exception {
        productoService.registrarProducto(new ProductoRequestDto(
                "PROD-NOTIF-01",
                "Servidor Rack",
                "Servidor empresarial de 64 núcleos",
                new BigDecimal("25000000.00")
        ));

        productoPublicacionJob.ejecutarPublicacionDeProductosPendientes();
        jmsTemplate.setReceiveTimeout(2000);
        jmsTemplate.receive(colaRegistro);

        Producto enOrigen = productoRepository.findByCodigoIgnoreCase("PROD-NOTIF-01").orElseThrow();

        ProductoNotificacionMessage confirmacionComponente2 = new ProductoNotificacionMessage(
                enOrigen.getId(),
                100L,
                "PROD-NOTIF-01",
                true,
                LocalDateTime.now()
        );

        // Enviar confirmación real a la cola de ActiveMQ para que @JmsListener la procese asíncronamente
        jmsTemplate.convertAndSend(colaNotificacion, confirmacionComponente2);

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    var actualizadoOpt = productoRepository.findByCodigoIgnoreCase("PROD-NOTIF-01");
                    assertThat(actualizadoOpt).isPresent();
                    Producto actualizado = actualizadoOpt.get();
                    assertThat(actualizado.isPublicado()).isTrue();
                    assertThat(actualizado.isNotificado()).isTrue();
                    assertThat(actualizado.getFechaNotificacion()).isNotNull();
                });

        mockMvc.perform(get("/api/v1/productos/PROD-NOTIF-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigo").value("PROD-NOTIF-01"))
                .andExpect(jsonPath("$.publicado").value(true))
                .andExpect(jsonPath("$.notificado").value(true));
    }

    @Test
    @DisplayName("8. Debe recuperar y reenviar productos con publicado=true y notificado=false tras espera sin confirmación")
    void debeReenviarProductosSinConfirmacionEnDestino() {
        productoService.registrarProducto(new ProductoRequestDto(
                "PROD-RECON-01",
                "Firewall Perimetral",
                "Appliance de seguridad de red empresarial",
                new BigDecimal("15000000.00")
        ));

        Producto producto = productoRepository.findByCodigoIgnoreCase("PROD-RECON-01").orElseThrow();
        producto.setPublicado(true);
        producto.setNotificado(false);
        producto.setFechaPublicacion(LocalDateTime.now().minusSeconds(30));
        producto.setIntentosPublicacion(1);
        productoRepository.save(producto);

        productoPublicacionJob.ejecutarPublicacionDeProductosPendientes();

        verify(productoJmsPublisher, times(1)).publicarProducto(any(ProductoEventMessage.class));
        Producto reenviado = productoRepository.findByCodigoIgnoreCase("PROD-RECON-01").orElseThrow();
        assertThat(reenviado.isPublicado()).isTrue();
        assertThat(reenviado.getIntentosPublicacion()).isEqualTo(2);

        jmsTemplate.setReceiveTimeout(2000);
        Message msgReenviado = jmsTemplate.receive(colaRegistro);
        assertThat(msgReenviado).isNotNull();
    }

    @Test
    @DisplayName("9. Debe verificar la generación del esquema grupoasd_dev y la tabla producto con sus columnas requeridas")
    void debeGenerarEsquemaGrupoAsdDevYTablaProducto() {
        Integer countTabla = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_SCHEMA) = 'grupoasd_dev' AND LOWER(TABLE_NAME) = 'producto'",
                Integer.class
        );
        assertThat(countTabla).isNotNull().isGreaterThan(0);

        Integer countColumnasControl = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_SCHEMA) = 'grupoasd_dev' AND LOWER(TABLE_NAME) = 'producto' AND LOWER(COLUMN_NAME) IN ('codigo', 'publicado', 'notificado')",
                Integer.class
        );
        assertThat(countColumnasControl).isEqualTo(3);
    }
}
