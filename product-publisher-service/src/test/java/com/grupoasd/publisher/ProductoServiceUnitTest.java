package com.grupoasd.publisher;

import com.grupoasd.publisher.domain.entity.Producto;
import com.grupoasd.publisher.dto.ProductoRequestDto;
import com.grupoasd.publisher.dto.ProductoResponseDto;
import com.grupoasd.publisher.dto.message.ProductoEventMessage;
import com.grupoasd.publisher.dto.message.ProductoNotificacionMessage;
import com.grupoasd.publisher.exception.ProductoDuplicadoException;
import com.grupoasd.publisher.exception.ProductoNoEncontradoException;
import com.grupoasd.publisher.mapper.ProductoMapper;
import com.grupoasd.publisher.messaging.ProductoJmsPublisher;
import com.grupoasd.publisher.messaging.ProductoNotificacionListener;
import com.grupoasd.publisher.repository.ProductoRepository;
import com.grupoasd.publisher.scheduler.ProductoPublicacionJob;
import com.grupoasd.publisher.service.impl.ProductoServiceImpl;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias puras (JUnit 5 + Mockito, sin levantar contexto Spring ni broker real)
 * para validar de forma aislada la lógica de negocio de ProductoServiceImpl, ProductoMapper,
 * ProductoPublicacionJob y ProductoNotificacionListener en el Componente 1.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("all")
class ProductoServiceUnitTest {

    @Mock
    private ProductoRepository productoRepository;

    @Mock
    private ProductoJmsPublisher productoJmsPublisher;

    private ProductoMapper productoMapper;
    private ProductoServiceImpl productoService;
    private ProductoPublicacionJob productoPublicacionJob;
    private Validator validator;
    private ProductoNotificacionListener productoNotificacionListener;

    @BeforeEach
    void setUp() {
        productoMapper = new ProductoMapper();
        productoService = new ProductoServiceImpl(productoRepository, productoMapper);
        productoPublicacionJob = new ProductoPublicacionJob(productoService, productoJmsPublisher, productoMapper);
        ReflectionTestUtils.setField(productoPublicacionJob, "batchSize", 50);
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        productoNotificacionListener = new ProductoNotificacionListener(productoService, validator);
    }

    @Test
    @DisplayName("1. Debe registrar un producto válido normalizando su código a mayúsculas y con publicado=false, notificado=false")
    void debeRegistrarProductoValidoNormalizandoCodigo() {
        // Arrange
        ProductoRequestDto request = new ProductoRequestDto(
                "  prod-unit-01  ",
                " Teclado Mecánico ",
                " Teclado mecánico retroiluminado profesional ",
                new BigDecimal("250000.00")
        );

        when(productoRepository.existsByCodigoIgnoreCase("PROD-UNIT-01")).thenReturn(false);
        when(productoRepository.save(any(Producto.class))).thenAnswer(invocation -> {
            Producto p = invocation.getArgument(0);
            p.setId(1L);
            return p;
        });

        // Act
        ProductoResponseDto response = productoService.registrarProducto(request);

        // Assert
        ArgumentCaptor<Producto> captor = ArgumentCaptor.forClass(Producto.class);
        verify(productoRepository).save(captor.capture());
        Producto entidadGuardada = captor.getValue();

        assertThat(entidadGuardada.getCodigo()).isEqualTo("PROD-UNIT-01");
        assertThat(entidadGuardada.getNombre()).isEqualTo("Teclado Mecánico");
        assertThat(entidadGuardada.isPublicado()).isFalse();
        assertThat(entidadGuardada.isNotificado()).isFalse();
        assertThat(entidadGuardada.getIntentosPublicacion()).isZero();

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getCodigo()).isEqualTo("PROD-UNIT-01");
        assertThat(response.isPublicado()).isFalse();
        assertThat(response.isNotificado()).isFalse();
    }

    @Test
    @DisplayName("2. Debe rechazar el registro cuando el código está duplicado sin invocar save()")
    void debeRechazarCodigoDuplicado() {
        // Arrange
        ProductoRequestDto request = new ProductoRequestDto(
                "prod-dup-99",
                "Mouse Óptico",
                "Mouse inalámbrico ergonómico",
                new BigDecimal("120000.00")
        );

        when(productoRepository.existsByCodigoIgnoreCase("PROD-DUP-99")).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> productoService.registrarProducto(request))
                .isInstanceOf(ProductoDuplicadoException.class)
                .hasMessageContaining("PROD-DUP-99");

        verify(productoRepository, never()).save(any(Producto.class));
    }

    @Test
    @DisplayName("3. Debe rechazar datos de entrada inválidos mediante las reglas de validación de ProductoRequestDto")
    void debeRechazarDatosInvalidosEnRequestDto() {
        // Arrange
        ProductoRequestDto requestInvalido = new ProductoRequestDto(
                "",
                "A",
                "Cort",
                new BigDecimal("-15.00")
        );

        // Act
        Set<ConstraintViolation<ProductoRequestDto>> violaciones = validator.validate(requestInvalido);

        // Assert
        assertThat(violaciones).isNotEmpty();
        assertThat(violaciones)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("codigo", "nombre", "descripcion", "precio");
    }

    @Test
    @DisplayName("4. Debe construir correctamente el mensaje ProductoEventMessage que se enviará a ActiveMQ")
    void debeConstruirCorrectamenteMensajeParaActiveMQ() {
        // Arrange
        Producto entidad = new Producto(
                "PROD-MAP-01",
                "Monitor 4K",
                "Monitor profesional 32 pulgadas UHD",
                new BigDecimal("1800000.00")
        );
        entidad.setId(42L);

        // Act
        ProductoEventMessage mensaje = productoMapper.toEventMessage(entidad);

        // Assert
        assertThat(mensaje.getIdOrigen()).isEqualTo(42L);
        assertThat(mensaje.getCodigo()).isEqualTo("PROD-MAP-01");
        assertThat(mensaje.getNombre()).isEqualTo("Monitor 4K");
        assertThat(mensaje.getDescripcion()).isEqualTo("Monitor profesional 32 pulgadas UHD");
        assertThat(mensaje.getPrecio()).isEqualByComparingTo("1800000.00");
        assertThat(mensaje.getFechaCreacion()).isEqualTo(entidad.getFechaCreacion());
        assertThat(mensaje.getFechaEnvioCola()).isNotNull();
    }

    @Test
    @DisplayName("5. El Job debe identificar productos pendientes (publicado=false), publicarlos en ActiveMQ y actualizar publicado=true")
    void debeIdentificarPendientesPublicarYMarcarPublicadoTrue() {
        // Arrange
        Producto pendiente = new Producto(
                "PROD-PEND-01",
                "Impresora Láser",
                "Impresora láser multifuncional de red",
                new BigDecimal("1450000.00")
        );
        pendiente.setId(7L);

        when(productoRepository.findByPublicadoFalseOrderByFechaCreacionAsc(any(Pageable.class)))
                .thenReturn(List.of(pendiente));
        when(productoRepository.findByPublicadoTrueAndNotificadoFalseAndFechaPublicacionBeforeOrderByFechaCreacionAsc(any(), any(Pageable.class)))
                .thenReturn(List.of());
        when(productoRepository.findById(7L)).thenReturn(Optional.of(pendiente));

        // Act
        productoPublicacionJob.ejecutarPublicacionDeProductosPendientes();

        // Assert
        ArgumentCaptor<ProductoEventMessage> msgCaptor = ArgumentCaptor.forClass(ProductoEventMessage.class);
        verify(productoJmsPublisher).publicarProducto(msgCaptor.capture());
        assertThat(msgCaptor.getValue().getCodigo()).isEqualTo("PROD-PEND-01");

        assertThat(pendiente.isPublicado()).isTrue();
        assertThat(pendiente.isNotificado()).isFalse();
        assertThat(pendiente.getFechaPublicacion()).isNotNull();
        assertThat(pendiente.getIntentosPublicacion()).isEqualTo(1);
        assertThat(pendiente.getUltimoErrorPublicacion()).isNull();
        verify(productoRepository).save(pendiente);
    }

    @Test
    @DisplayName("6. Debe mantener el producto con publicado=false y registrar el error cuando la publicación en ActiveMQ falla")
    void debeMantenerProductoPendienteCuandoPublicacionFalla() {
        // Arrange
        Producto pendiente = new Producto(
                "PROD-FAIL-01",
                "UPS Empresarial",
                "Sistema de alimentación ininterrumpida 3KVA",
                new BigDecimal("3100000.00")
        );
        pendiente.setId(9L);

        when(productoRepository.findByPublicadoFalseOrderByFechaCreacionAsc(any(Pageable.class)))
                .thenReturn(List.of(pendiente));
        when(productoRepository.findByPublicadoTrueAndNotificadoFalseAndFechaPublicacionBeforeOrderByFechaCreacionAsc(any(), any(Pageable.class)))
                .thenReturn(List.of());
        when(productoRepository.findById(9L)).thenReturn(Optional.of(pendiente));

        doThrow(new RuntimeException("Conexión rechazada por broker ActiveMQ"))
                .when(productoJmsPublisher).publicarProducto(any(ProductoEventMessage.class));

        // Act
        productoPublicacionJob.ejecutarPublicacionDeProductosPendientes();

        // Assert
        assertThat(pendiente.isPublicado()).isFalse();
        assertThat(pendiente.isNotificado()).isFalse();
        assertThat(pendiente.getIntentosPublicacion()).isEqualTo(1);
        assertThat(pendiente.getUltimoErrorPublicacion()).contains("Conexión rechazada por broker ActiveMQ");
        verify(productoRepository).save(pendiente);
    }

    @Test
    @DisplayName("7. Debe actualizar notificado=true y fechaNotificacion cuando se procesa una confirmación válida")
    void debeActualizarNotificadoCuandoSeProcesaConfirmacionValida() {
        // Arrange
        Producto publicado = new Producto(
                "PROD-CONF-01",
                "Switch Core",
                "Switch fibra óptica 10GbE administrable",
                new BigDecimal("8900000.00")
        );
        publicado.setId(15L);
        publicado.marcarComoPublicado();

        LocalDateTime fechaDestino = LocalDateTime.now();
        ProductoNotificacionMessage confirmacion = new ProductoNotificacionMessage(
                15L,
                200L,
                "PROD-CONF-01",
                true,
                fechaDestino
        );

        when(productoRepository.findByCodigoIgnoreCase("PROD-CONF-01")).thenReturn(Optional.of(publicado));

        // Act
        productoNotificacionListener.recibirNotificacionDePersistencia(confirmacion);

        // Assert
        assertThat(publicado.isPublicado()).isTrue();
        assertThat(publicado.isNotificado()).isTrue();
        assertThat(publicado.getFechaNotificacion()).isEqualTo(fechaDestino);
        verify(productoRepository).save(publicado);
    }

    @Test
    @DisplayName("8. Debe ignorar de forma segura una confirmación repetida y rechazar confirmaciones inválidas")
    void debeManejarDeManeraSeguraConfirmacionRepetidaOInvalida() {
        // Arrange — Caso A: Confirmación repetida cuando ya estaba notificado=true
        LocalDateTime fechaOriginal = LocalDateTime.now().minusMinutes(5);
        Producto yaNotificado = new Producto(
                "PROD-REP-01",
                "Access Point WiFi 6",
                "Punto de acceso corporativo doble banda",
                new BigDecimal("780000.00")
        );
        yaNotificado.setId(22L);
        yaNotificado.marcarComoNotificado(fechaOriginal);

        ProductoNotificacionMessage confirmacionRepetida = new ProductoNotificacionMessage(
                22L,
                301L,
                "PROD-REP-01",
                true,
                LocalDateTime.now()
        );

        when(productoRepository.findByCodigoIgnoreCase("PROD-REP-01")).thenReturn(Optional.of(yaNotificado));

        // Act — Caso A
        productoService.procesarConfirmacionDePersistencia(confirmacionRepetida);

        // Assert — Caso A: conserva la fecha original y no vuelve a escribir en BD
        assertThat(yaNotificado.isNotificado()).isTrue();
        assertThat(yaNotificado.getFechaNotificacion()).isEqualTo(fechaOriginal);
        verify(productoRepository, never()).save(yaNotificado);

        // Arrange & Act & Assert — Caso B: Confirmación inválida (código vacío o nulo)
        ProductoNotificacionMessage confirmacionInvalida = new ProductoNotificacionMessage(
                null,
                null,
                "   ",
                true,
                null
        );

        assertThatThrownBy(() -> productoNotificacionListener.recibirNotificacionDePersistencia(confirmacionInvalida))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("No debe aceptar una confirmación si el idOrigen pertenece a otro producto")
    void debeRechazarConfirmacionConIdentidadInconsistente() {
        Producto otroProducto = new Producto(
                "PROD-OTRO-01", "Monitor", "Monitor de oficina",
                new BigDecimal("750000.00"));
        otroProducto.setId(51L);

        when(productoRepository.findByCodigoIgnoreCase("PROD-ERRONEO-01"))
                .thenReturn(Optional.empty());
        when(productoRepository.findById(51L)).thenReturn(Optional.of(otroProducto));

        ProductoNotificacionMessage confirmacion = new ProductoNotificacionMessage(
                51L, 70L, "PROD-ERRONEO-01", true, LocalDateTime.now());

        assertThatThrownBy(() -> productoService.procesarConfirmacionDePersistencia(confirmacion))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconsistente");
        assertThat(otroProducto.isNotificado()).isFalse();
        verify(productoRepository, never()).save(any(Producto.class));
    }

    @Test
    @DisplayName("No debe deshacer una confirmación exitosa cuando llega persistido=false tarde")
    void noDebeRevertirConfirmacionExitosaPorNotificacionNegativaTardia() {
        Producto confirmado = new Producto(
                "PROD-CONFIRMADO-01", "Monitor", "Monitor de oficina",
                new BigDecimal("750000.00"));
        confirmado.setId(52L);
        LocalDateTime fechaOriginal = LocalDateTime.now().minusMinutes(2);
        confirmado.marcarComoNotificado(fechaOriginal);

        when(productoRepository.findByCodigoIgnoreCase("PROD-CONFIRMADO-01"))
                .thenReturn(Optional.of(confirmado));
        ProductoNotificacionMessage negativa = new ProductoNotificacionMessage(
                52L, 71L, "PROD-CONFIRMADO-01", false, LocalDateTime.now());

        productoService.procesarConfirmacionDePersistencia(negativa);

        assertThat(confirmado.isNotificado()).isTrue();
        assertThat(confirmado.getFechaNotificacion()).isEqualTo(fechaOriginal);
        verify(productoRepository, never()).save(any(Producto.class));
    }

    @Test
    @DisplayName("9. Debe lanzar ProductoNoEncontradoException cuando se consulta un código inexistente")
    void debeLanzarExcepcionCuandoProductoNoExiste() {
        // Arrange
        when(productoRepository.findByCodigoIgnoreCase("NO-EXISTE")).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> productoService.obtenerPorCodigo("no-existe"))
                .isInstanceOf(ProductoNoEncontradoException.class)
                .hasMessageContaining("NO-EXISTE");
    }
}
