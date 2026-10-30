package com.grupoasd.consumer.repository;

import com.grupoasd.consumer.domain.entity.ProductoDestino;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repositorio Spring Data JPA para la tabla producto del esquema grupoasd_prod.
 */
public interface ProductoDestinoRepository extends JpaRepository<ProductoDestino, Long> {

    /**
     * Busca un producto en grupoasd_prod por su código único.
     */
    Optional<ProductoDestino> findByCodigoIgnoreCase(String codigo);
}
