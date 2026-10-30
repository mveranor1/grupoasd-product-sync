package com.grupoasd.publisher.service;

import com.grupoasd.publisher.domain.entity.Producto;
import com.grupoasd.publisher.dto.ProductoRequestDto;
import com.grupoasd.publisher.dto.ProductoResponseDto;
import com.grupoasd.publisher.dto.message.ProductoNotificacionMessage;

import java.util.List;

/**
 * Contrato de servicio para la gestión de productos en el esquema de origen (grupoasd_dev).
 */
public interface ProductoService {

    /**
     * Registra un nuevo producto validando que su código no esté duplicado.
     */
    ProductoResponseDto registrarProducto(ProductoRequestDto requestDto);

    /**
     * Obtiene el listado completo de productos registrados en el sistema de origen.
     */
    List<ProductoResponseDto> listarProductos();

    /**
     * Consulta mediante API REST los productos que aún están pendientes de publicación (publicado = false).
     */
    List<ProductoResponseDto> listarProductosPendientesDePublicacion();

    /**
     * Consulta un producto por su código único.
     */
    ProductoResponseDto obtenerPorCodigo(String codigo);

    /**
     * Obtiene los productos pendientes de publicación en ActiveMQ (publicado = false).
     */
    List<Producto> obtenerProductosPendientesDePublicacion(int limite);

    /**
     * Obtiene los productos que tienen publicado = true pero siguen con notificado = false
     * después de varios segundos (por caída de red o fallo temporal en destino), para reenviarlos automáticamente.
     */
    List<Producto> obtenerProductosSinConfirmacionEnDestino(int limite, int segundosEspera);

    /**
     * Actualiza el estado de un producto a publicado = true tras enviarse exitosamente a ActiveMQ.
     */
    void marcarProductoComoPublicado(Long productoId);

    /**
     * Registra un fallo de publicación manteniendo publicado = false para reintento posterior.
     */
    void registrarFalloDePublicacion(Long productoId, String detalleError);

    /**
     * Actualiza el campo notificado = true cuando el Componente 2 confirma
     * que el producto fue persistido en el esquema grupoasd_prod.
     */
    void procesarConfirmacionDePersistencia(ProductoNotificacionMessage notificacion);
}
