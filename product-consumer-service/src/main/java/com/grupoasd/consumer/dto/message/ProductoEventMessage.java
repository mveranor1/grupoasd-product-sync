package com.grupoasd.consumer.dto.message;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Mensaje recibido por el Componente 2 desde la cola de ActiveMQ con toda la información del producto.
 */
public class ProductoEventMessage {

    private Long idOrigen;

    @NotBlank(message = "El código del producto en el mensaje no puede estar vacío")
    @Size(min = 3, max = 60, message = "El código del producto debe tener entre 3 y 60 caracteres")
    private String codigo;

    @NotBlank(message = "El nombre del producto en el mensaje no puede estar vacío")
    @Size(min = 2, max = 150, message = "El nombre del producto debe tener entre 2 y 150 caracteres")
    private String nombre;

    @NotBlank(message = "La descripción del producto en el mensaje no puede estar vacía")
    @Size(min = 5, max = 500, message = "La descripción del producto debe tener entre 5 y 500 caracteres")
    private String descripcion;

    @NotNull(message = "El precio del producto en el mensaje es obligatorio")
    @DecimalMin(value = "0.01", inclusive = true, message = "El precio del producto debe ser mayor a cero")
    private BigDecimal precio;

    @NotNull(message = "La fecha de creación del producto es obligatoria")
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
