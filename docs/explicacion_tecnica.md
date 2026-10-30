# Explicación Técnica: Identificación de Productos Pendientes, Prevención de Duplicados y Manejo de Errores

## 1. Diferencia Arquitectónica entre `publicado` y `notificado`

En el esquema de origen (`grupoasd_dev.producto`), la solución modela de forma explícita dos fases independientes del ciclo de vida de sincronización asíncrona:

1. **`publicado` (`boolean`, por defecto `false`):**
   - Indica que el Job programado (`ProductoPublicacionJob`) ya construyó el mensaje `ProductoEventMessage` y lo entregó exitosamente a la cola `grupoasd.productos.registro.queue` en **Apache ActiveMQ Classic**.
   - Se actualiza a `true` junto con la marca de tiempo `fecha_publicacion` y el incremento de `intentos_publicacion`.

2. **`notificado` (`boolean`, por defecto `false`):**
   - Indica que el **Componente 2 (`product-consumer-service`)** ya consumió el mensaje, lo persistió en el esquema de destino (`grupoasd_prod.producto`) y envió de vuelta un mensaje de confirmación (`ProductoNotificacionMessage`) por la cola `grupoasd.productos.notificacion.queue`.
   - Se actualiza a `true` junto con `fecha_notificacion` en `ProductoServiceImpl.procesarConfirmacionDePersistencia(...)`.

---

## 2. Mecanismo de Identificación de Productos Pendientes

1. **Registro Inicial:**
   - Cuando un cliente invoca `POST /api/v1/productos`, `ProductoServiceImpl.registrarProducto(...)` normaliza el código (`trim().toUpperCase()`), valida que no exista previamente y almacena la entidad con `publicado = false` y `notificado = false`.
2. **Detección Periódica por Lotes FIFO:**
   - `ProductoPublicacionJob` se ejecuta periódicamente según la propiedad externa `app.scheduler.publicacion.fixed-delay-ms` (configurable mediante `JOB_CRON_INTERVAL_MS` sin recompilar).
   - Consulta `findByPublicadoFalseOrderByFechaCreacionAsc(PageRequest.of(0, batchSize))` apoyándose en el índice sobre la columna `publicado`.
3. **Auto-Recuperación de Productos sin Confirmación (`publicado = true` y `notificado = false`):**
   - Si un producto fue publicado en ActiveMQ (`publicado = true`), pero transcurren más de 20 segundos sin recibir confirmación del Componente 2 (`notificado = false`), el método `reconciliarProductosSinConfirmacionEnDestino()` lo selecciona mediante `findByPublicadoTrueAndNotificadoFalseAndFechaPublicacionBeforeOrderByFechaCreacionAsc(...)` y lo reenvía automáticamente.

---

## 3. Prevención de Duplicados (Idempotencia de Extremo a Extremo)

1. **En el Sistema de Origen (`grupoasd_dev.producto`):**
   - Validación previa en capa de servicio con `existsByCodigoIgnoreCase(codigoNormalizado)`, lanzando `ProductoDuplicadoException` (HTTP `409 Conflict`).
   - Restricción de integridad `uk_producto_dev_codigo UNIQUE (codigo)` en PostgreSQL.
2. **En el Sistema de Destino (`grupoasd_prod.producto` — Consumidor Idempotente):**
   - Dado que ActiveMQ opera bajo semántica *At-Least-Once Delivery* (y el Job puede reenviar productos no confirmados), `ProductoSincronizacionServiceImpl.sincronizarProducto(...)` verifica primero `findByCodigoIgnoreCase(codigoNormalizado)`:
     - **Si no existe:** inserta el nuevo `ProductoDestino` en `grupoasd_prod.producto`.
     - **Si ya existe:** omite el `INSERT`, registra `[RESULTADO: DUPLICADO OMITIDO]` y reutiliza la entidad existente para reenviar la confirmación hacia el Componente 1.
   - **Protección ante Concurrencia:** La tabla `grupoasd_prod.producto` cuenta con la restricción `uk_producto_prod_codigo UNIQUE (codigo)`. Si dos hilos intentan insertar simultáneamente el mismo código, el bloque `catch (DataIntegrityViolationException)` recupera el registro recién insertado y envía la confirmación sin fallar la transacción.
3. **En la Recepción de Confirmaciones (`ProductoServiceImpl`):**
   - Si llega una confirmación repetida para un producto que ya tiene `notificado = true`, se ignora de forma segura conservando su `fecha_notificacion` original.

---

## 4. Manejo de Errores, Transacciones, Reintentos y Dead Letter Queue (`ActiveMQ.DLQ`)

1. **Fallo de ActiveMQ durante la Publicación (Componente 1):**
   - Cada producto del lote se actualiza mediante transacciones independientes (`@Transactional(propagation = Propagation.REQUIRES_NEW)`).
   - Si `productoJmsPublisher.publicarProducto(...)` lanza excepción, el `catch` invoca `registrarFalloDePublicacion(...)`: mantiene `publicado = false`, incrementa `intentos_publicacion` y registra el motivo en `ultimo_error_publicacion` (truncado a 500 caracteres). El producto vuelve a ser seleccionado automáticamente en el siguiente ciclo del Job.
2. **Fallo de Validación o de Base de Datos en el Consumidor (Componente 2):**
   - `DefaultJmsListenerContainerFactory` está configurado con `Session.SESSION_TRANSACTED` (`setSessionTransacted(true)`).
   - Si el mensaje recibido es nulo, incumple las restricciones de Jakarta Bean Validation (`@NotBlank`, `@Positive`, `@Size`) o falla la escritura en `grupoasd_prod.producto`, se lanza excepción antes de emitir confirmación. Esto provoca **rollback** de la sesión JMS, evitando que un mensaje se confirme como exitoso si no fue persistido.
3. **Política de Reintentos y Cola de Mensajes Muertos (`ActiveMQ.DLQ`):**
   - `JmsConfig` configura un `RedeliveryPolicy` en `ActiveMQConnectionFactory` con:
     - `maximumRedeliveries = 3` (configurable por `ACTIVEMQ_MAX_REDELIVERIES`).
     - `initialRedeliveryDelay = 2000 ms`.
     - `backOffMultiplier = 2.0` y `useExponentialBackOff = true` (esperas de 2s, 4s y 8s).
   - Al agotarse los reintentos configurados, ActiveMQ transfiere automáticamente el mensaje fallido a la cola **`ActiveMQ.DLQ`**, aislando el mensaje defectuoso sin bloquear el procesamiento de los demás productos.
