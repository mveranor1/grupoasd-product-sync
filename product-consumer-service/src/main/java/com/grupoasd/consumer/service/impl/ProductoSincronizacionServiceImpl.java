package com.grupoasd.consumer.service.impl;

import com.grupoasd.consumer.domain.entity.ProductoDestino;
import com.grupoasd.consumer.dto.message.ProductoEventMessage;
import com.grupoasd.consumer.dto.message.ProductoNotificacionMessage;
import com.grupoasd.consumer.messaging.NotificacionJmsPublisher;
import com.grupoasd.consumer.repository.ProductoDestinoRepository;
import com.grupoasd.consumer.service.ProductoSincronizacionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Servicio del Componente 2 que procesa de forma 100% automática los productos recibidos
 * de ActiveMQ, los persiste en el esquema grupoasd_prod evitando duplicados y envía
 * la confirmación al Componente 1 para marcar notificado=true.
 */
@Service
@SuppressWarnings("all")
public class ProductoSincronizacionServiceImpl implements ProductoSincronizacionService {

    private static final Logger log = LoggerFactory.getLogger(ProductoSincronizacionServiceImpl.class);

    private final ProductoDestinoRepository productoDestinoRepository;
    private final NotificacionJmsPublisher notificacionJmsPublisher;

    public ProductoSincronizacionServiceImpl(ProductoDestinoRepository productoDestinoRepository,
                                             NotificacionJmsPublisher notificacionJmsPublisher) {
        this.productoDestinoRepository = productoDestinoRepository;
        this.notificacionJmsPublisher = notificacionJmsPublisher;
    }

    @Override
    @Transactional
    public ProductoDestino sincronizarProducto(ProductoEventMessage mensaje) {
        String codigoNormalizado = normalizarCodigo(mensaje.getCodigo());

        Optional<ProductoDestino> existenteOpt = productoDestinoRepository.findByCodigoIgnoreCase(codigoNormalizado);

        ProductoDestino productoPersistido;
        if (existenteOpt.isPresent()) {
            productoPersistido = existenteOpt.get();
            log.info("[COMPONENTE 2][RESULTADO: DUPLICADO OMITIDO] El producto codigo='{}' (idOrigen={}) ya existe en grupoasd_prod con idDestino={}. Se evita duplicado.",
                    codigoNormalizado, mensaje.getIdOrigen(), productoPersistido.getId());
        } else {
            ProductoDestino nuevoDestino = new ProductoDestino(
                    mensaje.getIdOrigen(),
                    codigoNormalizado,
                    mensaje.getNombre().trim(),
                    mensaje.getDescripcion().trim(),
                    mensaje.getPrecio(),
                    mensaje.getFechaCreacion() != null ? mensaje.getFechaCreacion() : LocalDateTime.now(),
                    LocalDateTime.now()
            );

            try {
                productoPersistido = productoDestinoRepository.save(nuevoDestino);

                log.info("[COMPONENTE 2][RESULTADO: PERSISTIDO OK] Producto almacenado automáticamente en grupoasd_prod.producto -> idDestino={}, idOrigen={}, codigo='{}', nombre='{}', precio={}",
                        productoPersistido.getId(),
                        productoPersistido.getIdOrigen(),
                        productoPersistido.getCodigo(),
                        productoPersistido.getNombre(),
                        productoPersistido.getPrecio());
            } catch (DataIntegrityViolationException ex) {
                productoPersistido = productoDestinoRepository.findByCodigoIgnoreCase(codigoNormalizado)
                        .orElseThrow(() -> ex);
                log.info("[COMPONENTE 2][RESULTADO: DUPLICADO CONCURRENTE OMITIDO] El producto codigo='{}' fue insertado concurrentemente (idDestino={}). Se reutiliza registro existente.",
                        codigoNormalizado, productoPersistido.getId());
            }
        }

        ProductoNotificacionMessage confirmacion = new ProductoNotificacionMessage(
                mensaje.getIdOrigen(),
                productoPersistido.getId(),
                productoPersistido.getCodigo(),
                true,
                productoPersistido.getFechaSincronizacion()
        );

        notificacionJmsPublisher.publicarConfirmacionDePersistencia(confirmacion);

        return productoPersistido;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductoDestino> listarProductosSincronizados() {
        return productoDestinoRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductoDestino> buscarPorCodigo(String codigo) {
        return productoDestinoRepository.findByCodigoIgnoreCase(normalizarCodigo(codigo));
    }

    private String normalizarCodigo(String codigo) {
        return codigo.trim().toUpperCase(Locale.ROOT);
    }
}
