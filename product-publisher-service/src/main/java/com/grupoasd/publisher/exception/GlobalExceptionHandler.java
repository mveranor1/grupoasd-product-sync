package com.grupoasd.publisher.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manejador centralizado de errores para la API REST del Componente 1.
 * Registra cada error en el log y devuelve un mensaje claro y específico al cliente.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ProductoDuplicadoException.class)
    public ResponseEntity<ApiErrorResponse> handleProductoDuplicado(
            ProductoDuplicadoException ex, HttpServletRequest request) {
        log.warn("[API REST][ERROR DUPLICADO] Intento de registro con código duplicado en {}: {}",
                request.getRequestURI(), ex.getMessage());
        return construirRespuestaError(
                HttpStatus.CONFLICT,
                "Código de Producto Duplicado",
                ex.getMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(ProductoNoEncontradoException.class)
    public ResponseEntity<ApiErrorResponse> handleProductoNoEncontrado(
            ProductoNoEncontradoException ex, HttpServletRequest request) {
        log.warn("[API REST][NO ENCONTRADO] Consulta de producto inexistente en {}: {}",
                request.getRequestURI(), ex.getMessage());
        return construirRespuestaError(
                HttpStatus.NOT_FOUND,
                "Producto No Encontrado",
                ex.getMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationExceptions(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> validationErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            validationErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        String detalleUnificado = String.join(" | ", validationErrors.values());
        log.warn("[API REST][ERROR VALIDACION] Datos inválidos recibidos en {}: {}",
                request.getRequestURI(), validationErrors);
        return construirRespuestaError(
                HttpStatus.BAD_REQUEST,
                "Error de Validación de Datos",
                "La información del producto es inválida: " + detalleUnificado,
                request,
                validationErrors
        );
    }

    /**
     * Captura cuando el usuario envía texto en un campo numérico (ej: "precio": MIL PESOS o "precio": "MIL PESOS")
     * o cuando el JSON tiene errores de sintaxis.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleJsonMalFormadoOTipoInvalido(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        Map<String, String> detalleCampo = new LinkedHashMap<>();
        String mensajeExplicito = "El formato del JSON enviado es inválido. Verifique que los textos estén entre comillas y que 'precio' sea un número (ejemplo: 1000.00).";

        if (ex.getCause() instanceof InvalidFormatException invalidFormat
                && !invalidFormat.getPath().isEmpty()) {
            String nombreCampo = invalidFormat.getPath().get(0).getFieldName();
            Object valorRecibido = invalidFormat.getValue();
            mensajeExplicito = String.format(
                    "El campo '%s' recibió un valor inválido ('%s'). Debe ser un número válido (ejemplo: 1000.00).",
                    nombreCampo, valorRecibido
            );
            detalleCampo.put(nombreCampo, mensajeExplicito);
        } else {
            detalleCampo.put("json", "Sintaxis JSON inválida o tipo de dato incorrecto (ej: 'precio' debe ser numérico como 1000 y no texto como MIL PESOS)");
        }

        log.warn("[API REST][FORMATO INVALIDO] Error de lectura JSON en {}: {} | Causa: {}",
                request.getRequestURI(), mensajeExplicito, ex.getMostSpecificCause().getMessage());

        return construirRespuestaError(
                HttpStatus.BAD_REQUEST,
                "Formato de Datos o Tipo Inválido",
                mensajeExplicito,
                request,
                detalleCampo
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        log.error("[API REST][ERROR BD] Violación de integridad en base de datos: {}", ex.getMessage());
        return construirRespuestaError(
                HttpStatus.CONFLICT,
                "Conflicto en Base de Datos",
                "El código del producto ya se encuentra registrado en la base de datos",
                request,
                null
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(
            Exception ex, HttpServletRequest request) {
        log.error("[API REST][ERROR INTERNO] Error inesperado en {}: {}",
                request.getRequestURI(), ex.getMessage(), ex);
        return construirRespuestaError(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Error Interno del Servidor",
                "Error inesperado: " + ex.getMessage(),
                request,
                null
        );
    }

    private ResponseEntity<ApiErrorResponse> construirRespuestaError(
            HttpStatus status,
            String tituloError,
            String mensaje,
            HttpServletRequest request,
            Map<String, String> validationErrors) {
        ApiErrorResponse error = new ApiErrorResponse(
                LocalDateTime.now(),
                status.value(),
                tituloError,
                mensaje,
                request.getRequestURI(),
                validationErrors
        );
        return ResponseEntity.status(status).body(error);
    }
}
