package com.grupoasd.publisher.exception;

/**
 * Excepción lanzada cuando se intenta registrar un producto cuyo código ya existe.
 */
public class ProductoDuplicadoException extends RuntimeException {

    private final String codigo;

    public ProductoDuplicadoException(String codigo) {
        super(String.format("Ya existe un producto registrado con el código '%s'", codigo));
        this.codigo = codigo;
    }

    public String getCodigo() {
        return codigo;
    }
}
