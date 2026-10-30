package com.grupoasd.publisher.exception;

/**
 * Excepción lanzada cuando un producto solicitado no existe en el esquema grupoasd_dev.
 */
public class ProductoNoEncontradoException extends RuntimeException {

    public ProductoNoEncontradoException(String codigo) {
        super(String.format("No se encontró un producto con el código '%s' en grupoasd_dev", codigo));
    }
}
