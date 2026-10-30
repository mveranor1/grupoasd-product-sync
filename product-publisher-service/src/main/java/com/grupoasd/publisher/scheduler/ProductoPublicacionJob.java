package com.grupoasd.publisher.scheduler;

import com.grupoasd.publisher.domain.entity.Producto;
import com.grupoasd.publisher.dto.message.ProductoEventMessage;
import com.grupoasd.publisher.mapper.ProductoMapper;
import com.grupoasd.publisher.messaging.ProductoJmsPublisher;
import com.grupoasd.publisher.service.ProductoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Job programado que se ejecuta periódicamente según el intervalo configurado en application.yml.
 * Consulta los productos con publicado = false, publica un mensaje por cada producto en ActiveMQ
 * y actualiza su estado.
 */
@Component
@ConditionalOnProperty(
        value = "app.scheduler.publicacion.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ProductoPublicacionJob {

    private static final Logger log = LoggerFactory.getLogger(ProductoPublicacionJob.class);

    private final ProductoService productoService;
    private final ProductoJmsPublisher productoJmsPublisher;
    private final ProductoMapper productoMapper;

    @Value("${app.scheduler.publicacion.batch-size:50}")
    private int batchSize;

    @Value("${app.scheduler.publicacion.fixed-delay-ms:10000}")
    private long intervaloMs;

    @Value("${app.scheduler.publicacion.initial-delay-ms:3000}")
    private long retrasoInicialMs;

    public ProductoPublicacionJob(ProductoService productoService,
                                  ProductoJmsPublisher productoJmsPublisher,
                                  ProductoMapper productoMapper) {
        this.productoService = productoService;
        this.productoJmsPublisher = productoJmsPublisher;
        this.productoMapper = productoMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registrarConfiguracionDelJob() {
        log.info("[JOB][CONFIGURACION] Job de publicación activo: se ejecuta cada {} ms ({} segundos), "
                        + "primer ciclo a los {} ms, máximo {} productos por ciclo.",
                intervaloMs, intervaloMs / 1000.0, retrasoInicialMs, batchSize);
    }

    @Scheduled(
            fixedDelayString = "${app.scheduler.publicacion.fixed-delay-ms:10000}",
            initialDelayString = "${app.scheduler.publicacion.initial-delay-ms:3000}"
    )
    public void ejecutarPublicacionDeProductosPendientes() {
        log.debug("[JOB][INICIO] Buscando productos con publicado=false en grupoasd_dev...");

        List<Producto> productosPendientes = productoService.obtenerProductosPendientesDePublicacion(batchSize);

        if (!productosPendientes.isEmpty()) {
            log.info("[JOB][INICIO] Se encontraron {} producto(s) pendiente(s) de publicación (publicado=false) en grupoasd_dev.",
                    productosPendientes.size());

            int publicadosConExito = 0;
            int fallidos = 0;
            for (Producto producto : productosPendientes) {
                if (publicarProducto(producto)) {
                    publicadosConExito++;
                } else {
                    fallidos++;
                }
            }

            log.info("[JOB][RESUMEN] Procesados: {} | Publicados: {} | Fallidos (se reintentarán en el próximo ciclo): {}",
                    productosPendientes.size(), publicadosConExito, fallidos);
        }

        reconciliarProductosSinConfirmacionEnDestino();
    }

    /**
     * Auto-recuperación ante caídas de red o caídas temporales del Componente 2:
     * Si un producto ya salió (publicado = true) pero después de 20 segundos sigue con
     * notificado = false (no se ha confirmado que esté guardado en grupoasd_prod),
     * el Job lo reenvía automáticamente a ActiveMQ hasta que grupoasd_prod confirme.
     */
    private void reconciliarProductosSinConfirmacionEnDestino() {
        List<Producto> sinConfirmar = productoService.obtenerProductosSinConfirmacionEnDestino(batchSize, 20);
        if (sinConfirmar == null || sinConfirmar.isEmpty()) {
            return;
        }

        log.warn("[JOB][AUTO-RECUPERACION RED] Se detectaron {} producto(s) con publicado=true pero notificado=false (sin confirmar en grupoasd_prod). Reenviando automáticamente a ActiveMQ...",
                sinConfirmar.size());

        for (Producto producto : sinConfirmar) {
            if (publicarProducto(producto)) {
                log.info("[JOB][AUTO-RECUPERACION OK] Producto id={}, codigo='{}' reenviado a ActiveMQ para asegurar su registro en grupoasd_prod (intento #{}).",
                        producto.getId(), producto.getCodigo(), (producto.getIntentosPublicacion() == null ? 0 : producto.getIntentosPublicacion()) + 1);
            }
        }
    }

    private boolean publicarProducto(Producto producto) {
        try {
            ProductoEventMessage mensaje = productoMapper.toEventMessage(producto);
            productoJmsPublisher.publicarProducto(mensaje);
            productoService.marcarProductoComoPublicado(producto.getId());
            log.info("[JOB][OK] Producto id={}, codigo='{}' publicado en ActiveMQ y marcado con publicado=true.",
                    producto.getId(), producto.getCodigo());
            return true;
        } catch (Exception ex) {
            log.error("[JOB][ERROR] No se pudo publicar el producto id={}, codigo='{}': {}. "
                            + "Queda pendiente y se reintentará automáticamente cuando se restablezca la conexión.",
                    producto.getId(), producto.getCodigo(), ex.getMessage(), ex);
            productoService.registrarFalloDePublicacion(producto.getId(), ex.getMessage());
            return false;
        }
    }
}
