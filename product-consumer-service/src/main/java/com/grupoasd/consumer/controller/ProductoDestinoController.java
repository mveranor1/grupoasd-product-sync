package com.grupoasd.consumer.controller;

import com.grupoasd.consumer.domain.entity.ProductoDestino;
import com.grupoasd.consumer.service.ProductoSincronizacionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Controlador REST en el Componente 2 para comprobar que los productos consumidos
 * de ActiveMQ se almacenaron automáticamente en grupoasd_prod y ver sus logs.
 */
@RestController
@RequestMapping("/api/v1/productos-destino")
@SuppressWarnings("all")
@Tag(
        name = "Productos Sincronizados y Logs (Esquema Destino: grupoasd_prod)",
        description = "Permite comprobar en el Componente 2 los productos consumidos automáticamente desde ActiveMQ y guardados en grupoasd_prod"
)
public class ProductoDestinoController {

    private static final Logger log = LoggerFactory.getLogger(ProductoDestinoController.class);

    private final ProductoSincronizacionService productoSincronizacionService;

    @Value("${logging.file.name:logs/product-consumer-service.log}")
    private String rutaArchivoLog;

    public ProductoDestinoController(ProductoSincronizacionService productoSincronizacionService) {
        this.productoSincronizacionService = productoSincronizacionService;
    }

    @Operation(
            summary = "1. Listar todos los productos almacenados automáticamente en grupoasd_prod",
            description = "Devuelve todos los productos que el Componente 2 consumió automáticamente desde ActiveMQ y persistió en grupoasd_prod.producto."
    )
    @GetMapping
    public ResponseEntity<List<ProductoDestino>> listarProductosRecibidos() {
        log.info("[COMPONENTE 2][CONSULTA] Consultando listado de productos sincronizados en grupoasd_prod");
        return ResponseEntity.ok(productoSincronizacionService.listarProductosSincronizados());
    }

    @Operation(
            summary = "2. Consultar un producto específico en grupoasd_prod por su código",
            description = "Permite verificar la información completa de un producto sincronizado en grupoasd_prod."
    )
    @GetMapping("/{codigo}")
    public ResponseEntity<ProductoDestino> obtenerProductoPorCodigo(
            @Parameter(description = "Código único del producto", example = "PROD-001")
            @PathVariable String codigo) {
        log.info("[COMPONENTE 2][CONSULTA] Revisando información del producto codigo='{}' en grupoasd_prod", codigo);
        return productoSincronizacionService.buscarPorCodigo(codigo)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "3. Ver LOG de todos los movimientos de consumo y persistencia (con Fecha y Hora)",
            description = "Muestra todos los movimientos registrados en el Componente 2 (mensajes recibidos de ActiveMQ, persistencias en grupoasd_prod, confirmaciones ACK, duplicados omitidos y errores) con su fecha y hora exacta (yyyy-MM-dd HH:mm:ss)."
    )
    @GetMapping("/logs-movimientos")
    public ResponseEntity<List<String>> verLogsDeMovimientosDestino() {
        try {
            Path path = Paths.get(rutaArchivoLog);
            if (!Files.exists(path)) {
                return ResponseEntity.ok(List.of("Aún no se ha generado el archivo de logs en: " + path.toAbsolutePath()));
            }
            List<String> todasLasLineas = Files.readAllLines(path);
            List<String> movimientosRelevantes = todasLasLineas.stream()
                    .filter(linea -> linea.contains("[COMPONENTE-2]") &&
                            (linea.contains("[COMPONENTE 2]") ||
                             linea.contains("Notificación enviada") ||
                             linea.contains("Fallo durante")))
                    .toList();
            return ResponseEntity.ok(movimientosRelevantes);
        } catch (Exception e) {
            log.warn("Error leyendo el archivo de logs: {}", e.getMessage());
            return ResponseEntity.ok(List.of("Error leyendo el log: " + e.getMessage()));
        }
    }
}
