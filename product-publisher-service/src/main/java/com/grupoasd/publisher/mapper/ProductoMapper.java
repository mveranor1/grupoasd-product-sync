package com.grupoasd.publisher.mapper;

import com.grupoasd.publisher.domain.entity.Producto;
import com.grupoasd.publisher.dto.ProductoRequestDto;
import com.grupoasd.publisher.dto.ProductoResponseDto;
import com.grupoasd.publisher.dto.message.ProductoEventMessage;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Convierte entre la entidad Producto, los DTOs de la API REST y el mensaje para ActiveMQ.
 */
@Component
public class ProductoMapper {

    public Producto toEntity(ProductoRequestDto dto) {
        return new Producto(
                dto.getCodigo().trim().toUpperCase(Locale.ROOT),
                dto.getNombre().trim(),
                dto.getDescripcion().trim(),
                dto.getPrecio()
        );
    }

    public ProductoResponseDto toResponseDto(Producto entity) {
        ProductoResponseDto dto = new ProductoResponseDto();
        dto.setId(entity.getId());
        dto.setCodigo(entity.getCodigo());
        dto.setNombre(entity.getNombre());
        dto.setDescripcion(entity.getDescripcion());
        dto.setPrecio(entity.getPrecio());
        dto.setFechaCreacion(entity.getFechaCreacion());
        dto.setPublicado(entity.isPublicado());
        dto.setNotificado(entity.isNotificado());
        dto.setFechaPublicacion(entity.getFechaPublicacion());
        dto.setFechaNotificacion(entity.getFechaNotificacion());
        dto.setIntentosPublicacion(entity.getIntentosPublicacion());
        return dto;
    }

    public ProductoEventMessage toEventMessage(Producto entity) {
        return new ProductoEventMessage(
                entity.getId(),
                entity.getCodigo(),
                entity.getNombre(),
                entity.getDescripcion(),
                entity.getPrecio(),
                entity.getFechaCreacion(),
                LocalDateTime.now()
        );
    }
}
