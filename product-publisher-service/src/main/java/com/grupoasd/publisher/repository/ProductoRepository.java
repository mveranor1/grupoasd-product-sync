package com.grupoasd.publisher.repository;

import com.grupoasd.publisher.domain.entity.Producto;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio de acceso a datos para la entidad Producto en el esquema grupoasd_dev.
 */
public interface ProductoRepository extends JpaRepository<Producto, Long> {

    /**
     * Verifica si ya existe un producto con el código indicado (ignorando mayúsculas/minúsculas).
     */
    boolean existsByCodigoIgnoreCase(String codigo);

    /**
     * Busca un producto por su código único de negocio.
     */
    Optional<Producto> findByCodigoIgnoreCase(String codigo);

    /**
     * Obtiene los productos pendientes de publicación en ActiveMQ (publicado = false),
     * ordenados de forma ascendente por fecha de creación (FIFO).
     */
    List<Producto> findByPublicadoFalseOrderByFechaCreacionAsc(Pageable pageable);

    /**
     * Detecta productos que salieron hacia ActiveMQ (publicado = true) pero no han recibido
     * confirmación de guardado desde el Componente 2 (notificado = false) tras el tiempo límite
     * (por ejemplo, cuando se cae la red o el Componente 2 estaba apagado).
     */
    List<Producto> findByPublicadoTrueAndNotificadoFalseAndFechaPublicacionBeforeOrderByFechaCreacionAsc(
            LocalDateTime fechaLimite, Pageable pageable);

    /**
     * Obtiene todos los productos que aún no han sido confirmados en grupoasd_prod (notificado = false).
     */
    List<Producto> findByNotificadoFalseOrderByFechaCreacionAsc(Pageable pageable);
}
