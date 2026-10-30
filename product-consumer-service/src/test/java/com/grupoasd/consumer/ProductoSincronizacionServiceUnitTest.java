package com.grupoasd.consumer;

import com.grupoasd.consumer.domain.entity.ProductoDestino;
import com.grupoasd.consumer.dto.message.ProductoEventMessage;
import com.grupoasd.consumer.dto.message.ProductoNotificacionMessage;
import com.grupoasd.consumer.messaging.NotificacionJmsPublisher;
import com.grupoasd.consumer.messaging.ProductoConsumerListener;
import com.grupoasd.consumer.repository.ProductoDestinoRepository;
import com.grupoasd.consumer.service.impl.ProductoSincronizacionServiceImpl;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias puras (JUnit 5 + Mockito, sin levantar contexto Spring ni broker real)
 * para validar de forma aislada la lógica de sincronización, idempotencia, confirmación
 * y manejo de errores en el Componente 2.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("all")
class ProductoSincronizacionServiceUnitTest {

    @Mock
    private ProductoDestinoRepository productoDestinoRepository;

    @Mock
    private NotificacionJmsPublisher notificacionJmsPublisher;

    private ProductoSincronizacionServiceImpl sincronizacionService;
    private Validator validator;
    private ProductoConsumerListener productoConsumerListener;

    @BeforeEach
    void setUp() {
        sincronizacionService = new ProductoSincronizacionServiceImpl(
                productoDestinoRepository,
                notificacionJmsPublisher
        );
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        productoConsumerListener = new ProductoConsumerListener(sincronizacionService, validator);
    }

    @Test
    @DisplayName("1. Debe persistir un producto nuevo en grupoasd_prod normalizando su código")
    void debePersistirProductoNuevo() {
        // Arrange
        LocalDateTime fechaCreacion = LocalDateTime.now().minusMinutes(5);
        ProductoEventMessage mensaje = new ProductoEventMessage(
                10L,
                " prod-unit-dest-01 ",
                " Router Empresarial ",
                " Router Gigabit VPN Dual WAN ",
                new BigDecimal("950000.00"),
                fechaCreacion,
                LocalDateTime.now()
        );

        when(productoDestinoRepository.findByCodigoIgnoreCase("PROD-UNIT-DEST-01"))
                .thenReturn(Optional.empty());
        when(productoDestinoRepository.save(any(ProductoDestino.class))).thenAnswer(invocation -> {
            ProductoDestino guardado = invocation.getArgument(0);
            guardado.setId(500L);
            return guardado;
        });

        // Act
        ProductoDestino resultado = sincronizacionService.sincronizarProducto(mensaje);

        // Assert
        assertThat(resultado.getId()).isEqualTo(500L);
        assertThat(resultado.getIdOrigen()).isEqualTo(10L);
        assertThat(resultado.getCodigo()).isEqualTo("PROD-UNIT-DEST-01");
        assertThat(resultado.getNombre()).isEqualTo("Router Empresarial");
        assertThat(resultado.getDescripcion()).isEqualTo("Router Gigabit VPN Dual WAN");
        assertThat(resultado.getPrecio()).isEqualByComparingTo("950000.00");
        assertThat(resultado.getFechaCreacion()).isEqualTo(fechaCreacion);
        assertThat(resultado.getFechaSincronizacion()).isNotNull();
        verify(productoDestinoRepository).save(any(ProductoDestino.class));
    }

    @Test
    @DisplayName("2. Debe construir y enviar la confirmación correcta hacia el Componente 1 tras persistir")
    void debeConstruirYEnviarConfirmacionCorrecta() {
        // Arrange
        ProductoEventMessage mensaje = new ProductoEventMessage(
                15L,
                "PROD-CONF-DEST-02",
                "Switch PoE 48 Puertos",
                "Switch administrable PoE+ 740W",
                new BigDecimal("4200000.00"),
                LocalDateTime.now().minusMinutes(3),
                LocalDateTime.now()
        );

        when(productoDestinoRepository.findByCodigoIgnoreCase("PROD-CONF-DEST-02"))
                .thenReturn(Optional.empty());
        when(productoDestinoRepository.save(any(ProductoDestino.class))).thenAnswer(invocation -> {
            ProductoDestino guardado = invocation.getArgument(0);
            guardado.setId(801L);
            return guardado;
        });

        // Act
        ProductoDestino resultado = sincronizacionService.sincronizarProducto(mensaje);

        // Assert
        ArgumentCaptor<ProductoNotificacionMessage> captor =
                ArgumentCaptor.forClass(ProductoNotificacionMessage.class);
        verify(notificacionJmsPublisher).publicarConfirmacionDePersistencia(captor.capture());

        ProductoNotificacionMessage confirmacion = captor.getValue();
        assertThat(confirmacion.getIdOrigen()).isEqualTo(15L);
        assertThat(confirmacion.getIdDestino()).isEqualTo(801L);
        assertThat(confirmacion.getCodigo()).isEqualTo("PROD-CONF-DEST-02");
        assertThat(confirmacion.isPersistido()).isTrue();
        assertThat(confirmacion.getFechaPersistenciaDestino()).isEqualTo(resultado.getFechaSincronizacion());
    }

    @Test
    @DisplayName("3. Debe detectar un producto ya existente y evitar duplicados ante recepción repetida del mismo mensaje")
    void debeDetectarProductoExistenteYEvitarDuplicados() {
        // Arrange
        ProductoEventMessage mensajeRepetido = new ProductoEventMessage(
                10L,
                "PROD-EXISTENTE-01",
                "Router Empresarial",
                "Router Gigabit VPN Dual WAN",
                new BigDecimal("950000.00"),
                LocalDateTime.now().minusMinutes(5),
                LocalDateTime.now()
        );

        ProductoDestino yaExistente = new ProductoDestino(
                10L,
                "PROD-EXISTENTE-01",
                "Router Empresarial",
                "Router Gigabit VPN Dual WAN",
                new BigDecimal("950000.00"),
                LocalDateTime.now().minusMinutes(10),
                LocalDateTime.now().minusMinutes(4)
        );
        yaExistente.setId(77L);

        when(productoDestinoRepository.findByCodigoIgnoreCase("PROD-EXISTENTE-01"))
                .thenReturn(Optional.of(yaExistente));

        // Act — se invoca dos veces simulando entrega repetida de ActiveMQ
        ProductoDestino primero = sincronizacionService.sincronizarProducto(mensajeRepetido);
        ProductoDestino segundo = sincronizacionService.sincronizarProducto(mensajeRepetido);

        // Assert
        assertThat(primero.getId()).isEqualTo(77L);
        assertThat(segundo.getId()).isEqualTo(77L);
        verify(productoDestinoRepository, never()).save(any(ProductoDestino.class));
        verify(notificacionJmsPublisher, times(2))
                .publicarConfirmacionDePersistencia(any(ProductoNotificacionMessage.class));
    }

    @Test
    @DisplayName("4. Debe manejar colisión concurrente (DataIntegrityViolationException) sin duplicar y enviando confirmación")
    void debeManejarColisionConcurrenteDeUnicidadSinFallar() {
        // Arrange
        ProductoEventMessage mensaje = new ProductoEventMessage(
                33L,
                "PROD-CONC-01",
                "Balanceador de Carga",
                "Appliance balanceador de tráfico L4/L7",
                new BigDecimal("12500000.00"),
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now()
        );

        ProductoDestino insertadoPorOtroHilo = new ProductoDestino(
                33L,
                "PROD-CONC-01",
                "Balanceador de Carga",
                "Appliance balanceador de tráfico L4/L7",
                new BigDecimal("12500000.00"),
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now()
        );
        insertadoPorOtroHilo.setId(910L);

        when(productoDestinoRepository.findByCodigoIgnoreCase("PROD-CONC-01"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(insertadoPorOtroHilo));
        when(productoDestinoRepository.save(any(ProductoDestino.class)))
                .thenThrow(new DataIntegrityViolationException("uk_producto_prod_codigo duplicate key"));

        // Act
        ProductoDestino resultado = sincronizacionService.sincronizarProducto(mensaje);

        // Assert
        assertThat(resultado.getId()).isEqualTo(910L);
        verify(notificacionJmsPublisher).publicarConfirmacionDePersistencia(any(ProductoNotificacionMessage.class));
    }

    @Test
    @DisplayName("5. No debe enviar confirmación y debe propagar el error cuando falla la persistencia en base de datos")
    void noDebeConfirmarProductoCuyaPersistenciaHayaFallado() {
        // Arrange
        ProductoEventMessage mensaje = new ProductoEventMessage(
                44L,
                "PROD-DB-ERROR-01",
                "Storage SAN",
                "Cabina de almacenamiento flash empresarial",
                new BigDecimal("45000000.00"),
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        when(productoDestinoRepository.findByCodigoIgnoreCase("PROD-DB-ERROR-01"))
                .thenReturn(Optional.empty());
        when(productoDestinoRepository.save(any(ProductoDestino.class)))
                .thenThrow(new DataAccessResourceFailureException("Conexión a PostgreSQL interrumpida"));

        // Act & Assert
        assertThatThrownBy(() -> productoConsumerListener.consumirMensajeDeProducto(mensaje))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("PostgreSQL");

        verifyNoInteractions(notificacionJmsPublisher);
    }

    @Test
    @DisplayName("6. Debe rechazar mensajes nulos o con datos inválidos sin tocar el repositorio ni enviar confirmación")
    void debeRechazarMensajesInvalidosSinPersistirNiConfirmar() {
        // Arrange
        ProductoEventMessage mensajeInvalido = new ProductoEventMessage(
                99L,
                "",
                "X",
                "Mal",
                new BigDecimal("-100.00"),
                null,
                null
        );

        // Act & Assert — Mensaje nulo
        assertThatThrownBy(() -> productoConsumerListener.consumirMensajeDeProducto(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nulo");

        // Act & Assert — Mensaje con campos inválidos
        assertThatThrownBy(() -> productoConsumerListener.consumirMensajeDeProducto(mensajeInvalido))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inválidos");

        verifyNoInteractions(productoDestinoRepository);
        verifyNoInteractions(notificacionJmsPublisher);
    }
}
