package com.grupoasd.consumer;

import com.grupoasd.consumer.domain.entity.ProductoDestino;
import com.grupoasd.consumer.dto.message.ProductoEventMessage;
import com.grupoasd.consumer.repository.ProductoDestinoRepository;
import com.grupoasd.consumer.service.ProductoSincronizacionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * Prueba de carrera real: dos transacciones intentan insertar el mismo codigo
 * despues de comprobar simultaneamente que el producto no existe.
 *
 * Ejecutar con CONCURRENCY_TEST_DB_URL apuntando a una base PostgreSQL DE PRUEBAS.
 * Nunca utilizar la base de produccion ni grupoasd_db.
 */
@SpringBootTest(properties = {
        "app.messaging.queues.producto-registro=test.concurrency.productos.registro.queue",
        "app.messaging.queues.producto-notificacion=test.concurrency.productos.notificacion.queue"
})
@ActiveProfiles("test")
class ProductoConcurrencyPostgresTest {

    private static final String CODIGO = "PROD-CONCURRENT-REAL-01";
    private static final String TEST_DB_URL = System.getenv("CONCURRENCY_TEST_DB_URL");

    @DynamicPropertySource
    static void configurarPostgres(DynamicPropertyRegistry registry) {
        if (TEST_DB_URL != null && !TEST_DB_URL.isBlank()) {
            if (!TEST_DB_URL.contains("grupoasd_test_db")) {
                throw new IllegalArgumentException(
                        "CONCURRENCY_TEST_DB_URL debe apuntar a grupoasd_test_db, nunca a la base de produccion");
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
            registry.add("spring.activemq.broker-url", () ->
                    System.getenv().getOrDefault("CONCURRENCY_TEST_BROKER_URL",
                            "vm://localhost?broker.persistent=false"));
        }
    }

    @Autowired
    private ProductoSincronizacionService servicio;

    @MockitoSpyBean
    private ProductoDestinoRepository repositorio;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void preparar() {
        assumeTrue(TEST_DB_URL != null && !TEST_DB_URL.isBlank(),
                "Esta prueba requiere CONCURRENCY_TEST_DB_URL; no se ejecuta contra H2");
        assertThat(TEST_DB_URL).contains("grupoasd_test_db");
        // Borra únicamente el producto de esta prueba, sin afectar otras filas de test.
        jdbcTemplate.update("DELETE FROM grupoasd_prod.producto WHERE codigo = ?", CODIGO);
    }

    @Test
    void dosTransaccionesConcurrentesNoDebenDuplicarNiFallar() throws Exception {
        CountDownLatch ambasConsultaron = new CountDownLatch(2);
        doAnswer(invocation -> {
            // Los métodos de consulta de Spring Data son abstractos; Mockito no puede
            // ejecutar invocation.callRealMethod() sobre la interfaz del repositorio.
            // Usamos EntityManager para ejecutar una consulta JPA real contra PostgreSQL.
            Optional<ProductoDestino> result = entityManager.createQuery(
                            "SELECT p FROM ProductoDestino p WHERE UPPER(p.codigo) = :codigo",
                            ProductoDestino.class)
                    .setParameter("codigo", CODIGO)
                    .getResultStream()
                    .findFirst();
            if (result.isEmpty()) {
                ambasConsultaron.countDown();
                if (!ambasConsultaron.await(8, TimeUnit.SECONDS)) {
                    throw new AssertionError("No se alcanzaron dos consultas concurrentes");
                }
            }
            return result;
        }).when(repositorio).findByCodigoIgnoreCase(eq(CODIGO));

        ProductoEventMessage mensaje = new ProductoEventMessage(
                991L, CODIGO, "Producto concurrente",
                "Prueba de dos transacciones PostgreSQL",
                new BigDecimal("100000.00"), LocalDateTime.now(), LocalDateTime.now());

        try (var ejecutor = Executors.newFixedThreadPool(2)) {
            CountDownLatch inicio = new CountDownLatch(1);
            var llamada = (java.util.concurrent.Callable<Long>) () -> {
                inicio.await();
                return servicio.sincronizarProducto(mensaje).getId();
            };
            var primero = ejecutor.submit(llamada);
            var segundo = ejecutor.submit(llamada);
            inicio.countDown();

            // La restricción UNIQUE deja ganar a una transacción y hace fallar la
            // otra. Eso es esperado: el listener JMS hará rollback y reentrega.
            Long idGanador = null;
            int errores = 0;
            for (var futuro : java.util.List.of(primero, segundo)) {
                try {
                    Long id = futuro.get(20, TimeUnit.SECONDS);
                    assertThat(idGanador).isNull();
                    idGanador = id;
                } catch (ExecutionException ex) {
                    errores++;
                    assertThat(ex.getCause()).isInstanceOf(
                            org.springframework.dao.DataIntegrityViolationException.class);
                }
            }
            assertThat(idGanador).isNotNull();
            assertThat(errores).isEqualTo(1);
            // Simula la reentrega JMS, ahora con una nueva transacción JPA.
            assertThat(servicio.sincronizarProducto(mensaje).getId()).isEqualTo(idGanador);
        }

        assertThat(repositorio.findByCodigoIgnoreCase(CODIGO)).isPresent();
        Long filas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM grupoasd_prod.producto WHERE codigo = ?",
                Long.class, CODIGO);
        assertThat(filas).isEqualTo(1L);
    }
}
