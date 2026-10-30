package com.grupoasd.publisher.service.impl;

import com.grupoasd.publisher.domain.entity.Producto;
import com.grupoasd.publisher.dto.ProductoRequestDto;
import com.grupoasd.publisher.dto.ProductoResponseDto;
import com.grupoasd.publisher.dto.message.ProductoNotificacionMessage;
import com.grupoasd.publisher.exception.ProductoDuplicadoException;
import com.grupoasd.publisher.exception.ProductoNoEncontradoException;
import com.grupoasd.publisher.mapper.ProductoMapper;
import com.grupoasd.publisher.repository.ProductoRepository;
import com.grupoasd.publisher.service.ProductoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Servicio que gestiona el registro de productos y el control de los campos
 * 'publicado' y 'notificado' en el esquema grupoasd_dev.
 */
@Service
public class ProductoServiceImpl implements ProductoService {

    private static final Logger log = LoggerFactory.getLogger(ProductoServiceImpl.class);

    private final ProductoRepository productoRepository;
    private final ProductoMapper productoMapper;

    public ProductoServiceImpl(ProductoRepository productoRepository, ProductoMapper productoMapper) {
        this.productoRepository = productoRepository;
        this.productoMapper = productoMapper;
    }

    @Override
    @Transactional
    public ProductoResponseDto registrarProducto(ProductoRequestDto requestDto) {
        String codigoNormalizado = normalizarCodigo(requestDto.getCodigo());

        if (productoRepository.existsByCodigoIgnoreCase(codigoNormalizado)) {
            throw new ProductoDuplicadoException(codigoNormalizado);
        }

        Producto nuevoProducto = productoMapper.toEntity(requestDto);
        Producto productoGuardado = productoRepository.save(nuevoProducto);

        log.info("Producto registrado exitosamente en grupoasd_dev -> id={}, codigo='{}', publicado={}, notificado={}",
                productoGuardado.getId(),
                productoGuardado.getCodigo(),
                productoGuardado.isPublicado(),
                productoGuardado.isNotificado());

        return productoMapper.toResponseDto(productoGuardado);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductoResponseDto> listarProductos() {
        return productoRepository.findAll()
                .stream()
                .map(productoMapper::toResponseDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductoResponseDto> listarProductosPendientesDePublicacion() {
        return productoRepository.findByNotificadoFalseOrderByFechaCreacionAsc(crearPaginacionLote(100))
                .stream()
                .map(productoMapper::toResponseDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductoResponseDto obtenerPorCodigo(String codigo) {
        String codigoNormalizado = normalizarCodigo(codigo);
        Producto producto = productoRepository.findByCodigoIgnoreCase(codigoNormalizado)
                .orElseThrow(() -> new ProductoNoEncontradoException(codigoNormalizado));
        return productoMapper.toResponseDto(producto);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Producto> obtenerProductosPendientesDePublicacion(int limite) {
        return productoRepository.findByPublicadoFalseOrderByFechaCreacionAsc(crearPaginacionLote(limite));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Producto> obtenerProductosSinConfirmacionEnDestino(int limite, int segundosEspera) {
        LocalDateTime fechaLimite = LocalDateTime.now().minusSeconds(Math.max(5, segundosEspera));
        return productoRepository.findByPublicadoTrueAndNotificadoFalseAndFechaPublicacionBeforeOrderByFechaCreacionAsc(
                fechaLimite, crearPaginacionLote(limite));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void marcarProductoComoPublicado(Long productoId) {
        productoRepository.findById(productoId).ifPresent(producto -> {
            producto.marcarComoPublicado();
            productoRepository.save(producto);
            log.info("Estado actualizado en grupoasd_dev: producto id={}, codigo='{}' marcado como publicado=true",
                    producto.getId(), producto.getCodigo());
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrarFalloDePublicacion(Long productoId, String detalleError) {
        productoRepository.findById(productoId).ifPresent(producto -> {
            producto.registrarFalloPublicacion(detalleError);
            productoRepository.save(producto);
            log.warn("Fallo registrado en grupoasd_dev para producto id={}, codigo='{}' (intentos={}): {}",
                    producto.getId(), producto.getCodigo(), producto.getIntentosPublicacion(), detalleError);
        });
    }

    @Override
    @Transactional
    public void procesarConfirmacionDePersistencia(ProductoNotificacionMessage notificacion) {
        if (notificacion == null || notificacion.getCodigo() == null || notificacion.getCodigo().isBlank()) {
            throw new IllegalArgumentException("El mensaje de notificación no contiene un código de producto válido");
        }

        String codigoNormalizado = normalizarCodigo(notificacion.getCodigo());
        Optional<Producto> optionalProducto = productoRepository.findByCodigoIgnoreCase(codigoNormalizado);

        if (optionalProducto.isEmpty() && notificacion.getIdOrigen() != null) {
            optionalProducto = productoRepository.findById(notificacion.getIdOrigen());
        }

        if (!notificacion.isPersistido()) {
            optionalProducto.ifPresent(producto -> {
                producto.setNotificado(false);
                producto.setFechaNotificacion(null);
                productoRepository.save(producto);
            });
            log.warn("[COMPONENTE 1][RECHAZO EN COLA DESTINO] El Componente 2 RECHAZÓ el producto codigo='{}'. Se mantiene/actualiza notificado=false en grupoasd_dev (no quedó guardado en grupoasd_prod).",
                    codigoNormalizado);
            return;
        }

        Producto producto = optionalProducto.orElseThrow(
                () -> new ProductoNoEncontradoException(codigoNormalizado)
        );

        if (producto.isNotificado()) {
            log.info("Confirmación repetida ignorada de forma segura en grupoasd_dev -> Producto id={}, codigo='{}' ya tenía notificado=true",
                    producto.getId(), producto.getCodigo());
            return;
        }

        producto.marcarComoNotificado(notificacion.getFechaPersistenciaDestino());
        productoRepository.save(producto);

        log.info("Confirmación procesada en grupoasd_dev -> Producto id={}, codigo='{}' actualizado a notificado=true (idDestino={})",
                producto.getId(), producto.getCodigo(), notificacion.getIdDestino());
    }

    private String normalizarCodigo(String codigo) {
        return codigo.trim().toUpperCase(Locale.ROOT);
    }

    private PageRequest crearPaginacionLote(int limite) {
        return PageRequest.of(0, Math.max(1, limite));
    }
}
