package com.grupoasd.consumer.dto.message;

import java.time.LocalDateTime;

/**
 * Mensaje que envía el Componente 2 al Componente 1 para confirmar que el producto
 * fue persistido en grupoasd_prod y que debe cambiarse notificado = true.
 */
public class ProductoNotificacionMessage {

    private Long idOrigen;
    private Long idDestino;
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
