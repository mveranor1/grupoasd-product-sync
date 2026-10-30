package com.grupoasd.consumer.service;

import com.grupoasd.consumer.domain.entity.ProductoDestino;
import com.grupoasd.consumer.dto.message.ProductoEventMessage;

import java.util.List;
import java.util.Optional;

/**
 * Contrato de servicio para la sincronización automática, persistencia y consulta de productos
 * en el esquema de destino (grupoasd_prod) recibidos desde ActiveMQ.
 */
public interface ProductoSincronizacionService {

    /**
     * Procesa automáticamente cada mensaje válido recibido desde ActiveMQ:
     * 1. Verifica si el código ya existe en grupoasd_prod (idempotencia).
     * 2. Persiste el producto en grupoasd_prod si no existe.
     * 3. Publica la confirmación hacia la cola del Componente 1 para actualizar notificado = true.
     */
    ProductoDestino sincronizarProducto(ProductoEventMessage mensaje);

    /**
     * Permite consultar todos los productos sincronizados automáticamente en el esquema grupoasd_prod.
     */
    List<ProductoDestino> listarProductosSincronizados();

    /**
     * Permite consultar un producto específico en grupoasd_prod por su código.
     */
    Optional<ProductoDestino> buscarPorCodigo(String codigo);
}



