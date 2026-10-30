package com.grupoasd.publisher.dto.message;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * Mensaje de confirmación que recibe el Componente 1 desde el Componente 2
 * cuando el producto ya fue persistido en grupoasd_prod.
 */
public class ProductoNotificacionMessage {

    private Long idOrigen;
    private Long idDestino;

    @NotBlank(message = "El código del producto notificado es obligatorio")
    private String codigo;

    private boolean persistido;
    private LocalDateTime fechaPersistenciaDestino;

    public ProductoNotificacionMessage() {
    }

    public ProductoNotificacionMessage(Long idOrigen, Long idDestino, String codigo,
                                       boolean persistido, LocalDateTime fechaPersistenciaDestino) {
        this.idOrigen = idOrigen;
        this.idDestino = idDestino;
        this.codigo = codigo;
        this.persistido = persistido;
        this.fechaPersistenciaDestino = fechaPersistenciaDestino;
    }

    public Long getIdOrigen() {
        return idOrigen;
    }

    public void setIdOrigen(Long idOrigen) {
        this.idOrigen = idOrigen;
    }

    public Long getIdDestino() {
        return idDestino;
    }

    public void setIdDestino(Long idDestino) {
        this.idDestino = idDestino;
    }

    public String getCodigo() {
        return codigo;
    }

    public void setCodigo(String codigo) {
        this.codigo = codigo;
    }

    public boolean isPersistido() {
        return persistido;
    }

    public void setPersistido(boolean persistido) {
        this.persistido = persistido;
    }

    public LocalDateTime getFechaPersistenciaDestino() {
        return fechaPersistenciaDestino;
    }

    public void setFechaPersistenciaDestino(LocalDateTime fechaPersistenciaDestino) {
        this.fechaPersistenciaDestino = fechaPersistenciaDestino;
    }

    @Override
    public String toString() {
        return "ProductoNotificacionMessage{idOrigen=" + idOrigen +
                ", idDestino=" + idDestino +
                ", codigo='" + codigo + '\'' +
                ", persistido=" + persistido + '}';
    }
}
