package com.grupoasd.publisher.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entidad que representa la tabla 'producto' en el esquema de origen 'grupoasd_dev'.
 */
@Entity
@Table(
        name = "producto",
        schema = "grupoasd_dev",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_producto_dev_codigo", columnNames = "codigo")
        },
        indexes = {
                @Index(name = "idx_producto_dev_publicado", columnList = "publicado"),
                @Index(name = "idx_producto_dev_notificado", columnList = "notificado")
        }
)
public class Producto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "codigo", nullable = false, unique = true, length = 60)
    private String codigo;

    @Column(name = "nombre", nullable = false, length = 150)
    private String nombre;

    @Column(name = "descripcion", nullable = false, length = 500)
    private String descripcion;

    @Column(name = "precio", nullable = false, precision = 19, scale = 2)
    private BigDecimal precio;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    /**
     * Indica si el Job programado ya publicó este producto en ActiveMQ. Por defecto es false.
     */
    @Column(name = "publicado", nullable = false)
    private boolean publicado = false;

    /**
     * Indica si el Componente 2 ya confirmó que el producto quedó guardado en grupoasd_prod.
     * Por defecto es false y cambia a true cuando llega el mensaje de confirmación.
     */
    @Column(name = "notificado", nullable = false)
    private boolean notificado = false;

    @Column(name = "fecha_publicacion")
    private LocalDateTime fechaPublicacion;

    @Column(name = "fecha_notificacion")
    private LocalDateTime fechaNotificacion;

    @Column(name = "intentos_publicacion", nullable = false)
    private Integer intentosPublicacion = 0;

    @Column(name = "ultimo_error_publicacion", length = 500)
    private String ultimoErrorPublicacion;

    public Producto() {
    }

    public Producto(String codigo, String nombre, String descripcion, BigDecimal precio) {
        this.codigo = codigo;
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.precio = precio;
        this.fechaCreacion = LocalDateTime.now();
        this.publicado = false;
        this.notificado = false;
        this.intentosPublicacion = 0;
    }

    @PrePersist
    public void prePersist() {
        if (this.fechaCreacion == null) {
            this.fechaCreacion = LocalDateTime.now();
        }
        if (this.intentosPublicacion == null) {
            this.intentosPublicacion = 0;
        }
    }

    public void marcarComoPublicado() {
        this.publicado = true;
        this.fechaPublicacion = LocalDateTime.now();
        incrementarIntentosPublicacion();
        this.ultimoErrorPublicacion = null;
    }

    public void registrarFalloPublicacion(String mensajeError) {
        this.publicado = false;
        incrementarIntentosPublicacion();
        if (mensajeError != null && mensajeError.length() > 500) {
            this.ultimoErrorPublicacion = mensajeError.substring(0, 500);
        } else {
            this.ultimoErrorPublicacion = mensajeError;
        }
    }

    private void incrementarIntentosPublicacion() {
        this.intentosPublicacion = (this.intentosPublicacion == null ? 0 : this.intentosPublicacion) + 1;
    }

    public void marcarComoNotificado(LocalDateTime fechaConfirmacion) {
        this.publicado = true;
        if (this.fechaPublicacion == null) {
            this.fechaPublicacion = fechaConfirmacion != null ? fechaConfirmacion : LocalDateTime.now();
        }
        this.notificado = true;
        this.fechaNotificacion = fechaConfirmacion != null ? fechaConfirmacion : LocalDateTime.now();
        this.ultimoErrorPublicacion = null;
    }

    // Getters y Setters estándar de Java

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public boolean isPublicado() {
        return publicado;
    }

    public void setPublicado(boolean publicado) {
        this.publicado = publicado;
    }

    public boolean isNotificado() {
        return notificado;
    }

    public void setNotificado(boolean notificado) {
        this.notificado = notificado;
    }

    public LocalDateTime getFechaPublicacion() {
        return fechaPublicacion;
    }

    public void setFechaPublicacion(LocalDateTime fechaPublicacion) {
        this.fechaPublicacion = fechaPublicacion;
    }

    public LocalDateTime getFechaNotificacion() {
        return fechaNotificacion;
    }

    public void setFechaNotificacion(LocalDateTime fechaNotificacion) {
        this.fechaNotificacion = fechaNotificacion;
    }

    public Integer getIntentosPublicacion() {
        return intentosPublicacion;
    }

    public void setIntentosPublicacion(Integer intentosPublicacion) {
        this.intentosPublicacion = intentosPublicacion;
    }

    public String getUltimoErrorPublicacion() {
        return ultimoErrorPublicacion;
    }

    public void setUltimoErrorPublicacion(String ultimoErrorPublicacion) {
        this.ultimoErrorPublicacion = ultimoErrorPublicacion;
    }
}
