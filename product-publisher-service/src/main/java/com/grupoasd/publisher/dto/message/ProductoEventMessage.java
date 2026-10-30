package com.grupoasd.publisher.dto.message;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Mensaje que el Componente 1 publica en la cola de ActiveMQ por cada producto pendiente.
 * Contiene toda la información del producto.
 */
public class ProductoEventMessage {

    private Long idOrigen;
    private String codigo;
    private String nombre;
    private String descripcion;
    private BigDecimal precio;
    private LocalDateTime fechaCreacion;
    private LocalDateTime fechaEnvioCola;

    public ProductoEventMessage() {
    }

    public ProductoEventMessage(Long idOrigen, String codigo, String nombre, String descripcion,
                                BigDecimal precio, LocalDateTime fechaCreacion, LocalDateTime fechaEnvioCola) {
        this.idOrigen = idOrigen;
        this.codigo = codigo;
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.precio = precio;
        this.fechaCreacion = fechaCreacion;
        this.fechaEnvioCola = fechaEnvioCola;
    }

    public Long getIdOrigen() {
        return idOrigen;
    }

    public void setIdOrigen(Long idOrigen) {
        this.idOrigen = idOrigen;
    }

    public String getCodigo() {
        return codigo;
    }

    public void setCodigo(String codigo) {
        this.codigo = codigo;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public BigDecimal getPrecio() {
        return precio;
    }

    public void setPrecio(BigDecimal precio) {
        this.precio = precio;
    }

    public LocalDateTime getFechaCreacion() {
        return fechaCreacion;
    }

    public void setFechaCreacion(LocalDateTime fechaCreacion) {
        this.fechaCreacion = fechaCreacion;
    }

    public LocalDateTime getFechaEnvioCola() {
        return fechaEnvioCola;
    }

    public void setFechaEnvioCola(LocalDateTime fechaEnvioCola) {
        this.fechaEnvioCola = fechaEnvioCola;
    }

    @Override
    public String toString() {
        return "ProductoEventMessage{idOrigen=" + idOrigen +
                ", codigo='" + codigo + '\'' +
                ", nombre='" + nombre + '\'' +
                ", precio=" + precio +
                ", fechaCreacion=" + fechaCreacion + '}';
    }
}
