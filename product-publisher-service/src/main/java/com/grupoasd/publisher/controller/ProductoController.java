package com.grupoasd.publisher.controller;

import com.grupoasd.publisher.dto.ProductoRequestDto;
import com.grupoasd.publisher.dto.ProductoResponseDto;
import com.grupoasd.publisher.service.ProductoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Controlador REST para el registro, consulta y visualización de logs de movimientos en grupoasd_dev.
 */
@RestController
@RequestMapping("/api/v1/productos")
@SuppressWarnings("all")
@Tag(
        name = "Productos y Logs (Esquema Origen: grupoasd_dev)",
        description = "Permite registrar productos, consultar su estado publicado/notificado y ver el log de movimientos con fecha y hora"
)
public class ProductoController {

    private static final Logger log = LoggerFactory.getLogger(ProductoController.class);

    private final ProductoService productoService;

    @Value("${logging.file.name:logs/product-publisher-service.log}")
    private String rutaArchivoLog;

    public ProductoController(ProductoService productoService) {
        this.productoService = productoService;
    }

    @Operation(
            summary = "1. Registrar un nuevo producto en grupoasd_dev",
            description = "Crea el producto con publicado=false y notificado=false."
    )
    @PostMapping
    public ResponseEntity<ProductoResponseDto> registrarProducto(
            @Valid @RequestBody ProductoRequestDto requestDto) {
        log.info("[MOVIMIENTO API] Solicitud recibida para registrar producto codigo='{}', nombre='{}', precio={}",
                requestDto.getCodigo(), requestDto.getNombre(), requestDto.getPrecio());
        ProductoResponseDto response = productoService.registrarProducto(requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "2. Consultar productos pendientes o sin confirmar en destino (publicado = false o notificado = false)",
            description = "Muestra los productos que aún no han salido (publicado=false) o que salieron cuando hubo caída de red y aún no han sido confirmados por grupoasd_prod (notificado=false). Si esta lista está vacía ([]), significa que el 100% de tus productos ya están guardados y confirmados en grupoasd_prod."
    )
    @GetMapping("/pendientes-publicacion")
    public ResponseEntity<List<ProductoResponseDto>> listarProductosPendientesDePublicacion() {
        log.info("[MOVIMIENTO CONSULTA] Consultando productos pendientes o sin confirmar en destino (notificado=false) en grupoasd_dev");
        return ResponseEntity.ok(productoService.listarProductosPendientesDePublicacion());
    }

    @Operation(
            summary = "3. Listar todos los productos en grupoasd_dev",
            description = "Devuelve todos los productos registrados junto con sus estados publicado, notificado y sus fechas/horas."
    )
    @GetMapping
    public ResponseEntity<List<ProductoResponseDto>> listarProductos() {
        log.info("[MOVIMIENTO CONSULTA] Consultando listado completo de productos en grupoasd_dev");
        return ResponseEntity.ok(productoService.listarProductos());
    }

    @Operation(
            summary = "4. Consultar producto por código único",
            description = "Busca un producto por su código (ej: PROD-001) y muestra su estado publicado y notificado."
    )
    @GetMapping("/{codigo}")
    public ResponseEntity<ProductoResponseDto> obtenerProductoPorCodigo(
            @Parameter(description = "Código único del producto", example = "PROD-001")
            @PathVariable String codigo) {
        log.info("[MOVIMIENTO CONSULTA] Consultando detalle del producto codigo='{}' en grupoasd_dev", codigo);
        return ResponseEntity.ok(productoService.obtenerPorCodigo(codigo));
    }

    @Operation(
            summary = "5. Ver LOG de todos los movimientos y errores registrados (con Fecha y Hora)",
            description = "Muestra todos los movimientos registrados en el Componente 1 (creaciones, ejecuciones del Job, envíos a ActiveMQ, confirmaciones notificado=true y errores) con su fecha y hora exacta (yyyy-MM-dd HH:mm:ss)."
    )
    @GetMapping("/logs-movimientos")
    public ResponseEntity<List<String>> verLogsDeMovimientos() {
        try {
            Path path = Paths.get(rutaArchivoLog);
            if (!Files.exists(path)) {
                return ResponseEntity.ok(List.of("Aún no se ha generado el archivo de logs en: " + path.toAbsolutePath()));
            }
            List<String> todasLasLineas = Files.readAllLines(path);
            List<String> movimientosRelevantes = todasLasLineas.stream()
                    .filter(linea -> linea.contains("[COMPONENTE-1]") &&
                            (linea.contains("[MOVIMIENTO") ||
                             linea.contains("Producto registrado") ||
                             linea.contains("[JOB]") ||
                             linea.contains("[COMPONENTE 1]") ||
                             linea.contains("[API REST]") ||
                             linea.contains("Estado actualizado") ||
                             linea.contains("Confirmación procesada") ||
                             linea.contains("Notificación recibida") ||
                             linea.contains("Fallo registrado")))
                    .toList();
            return ResponseEntity.ok(movimientosRelevantes);
        } catch (Exception e) {
            log.warn("Error leyendo el archivo de logs: {}", e.getMessage());
            return ResponseEntity.ok(List.of("Error leyendo el log: " + e.getMessage()));
        }
    }
}
