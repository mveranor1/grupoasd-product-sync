# Evidencias Reales de Pruebas Automatizadas y Verificación Docker Compose

**Proyecto:** `grupoasd-product-sync`  
**Fecha de ejecución:** 28 de octubre de 2026  
**Entorno de ejecución:** Java 25.0.5, Spring Boot 3.5.16, Maven 3.9.4, PostgreSQL 16.15 (`grupoasd_test_db` para pruebas automatizadas / `grupoasd_db` para contenedores), Apache ActiveMQ Classic 6.3.2.

---

## 1. Resumen Ejecutivo de Ejecución (`mvn clean verify`)

```text
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary for Grupo ASD - Product Synchronization System 1.0.0:
[INFO] 
[INFO] Grupo ASD - Product Synchronization System ......... SUCCESS [  0.176 s]
[INFO] Product Publisher Service (Componente 1 - grupoasd_dev) SUCCESS [ 59.642 s]
[INFO] Product Consumer Service (Componente 2 - grupoasd_prod) SUCCESS [ 25.004 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  01:25 min
[INFO] Finished at: 2026-10-28T16:55:31-05:00
[INFO] ------------------------------------------------------------------------
```

| Módulo | Clase de Prueba | Categoría | Ejecutadas | Aprobadas | Fallidas | Errores | Estado |
|---|---|---|---:|---:|---:|---:|---|
| `product-publisher-service` | `ProductoServiceUnitTest` | Unitarias (JUnit 5 + Mockito) | 9 | 9 | 0 | 0 | `EJECUTADO Y APROBADO` |
| `product-publisher-service` | `ProductPublisherIntegrationTest` | Integración (`@SpringBootTest` + `MockMvc` + PostgreSQL + ActiveMQ) | 9 | 9 | 0 | 0 | `EJECUTADO Y APROBADO` |
| `product-publisher-service` | `FlujoCompletoSincronizacionE2ETest` | Flujo Completo E2E (App 1 -> ActiveMQ -> App 2 -> Confirmación -> App 1) | 3 | 3 | 0 | 0 | `EJECUTADO Y APROBADO` |
| `product-consumer-service` | `ProductoSincronizacionServiceUnitTest` | Unitarias (JUnit 5 + Mockito) | 6 | 6 | 0 | 0 | `EJECUTADO Y APROBADO` |
| `product-consumer-service` | `ProductConsumerIntegrationTest` | Integración (`@SpringBootTest` + PostgreSQL + ActiveMQ + `DLQ`) | 6 | 6 | 0 | 0 | `EJECUTADO Y APROBADO` |
| **TOTAL** | **5 suites de prueba** | **15 Unitarias + 15 Integración + 3 Flujo Completo E2E** | **33** | **33** | **0** | **0** | **`100% APROBADO`** |

---

## 2. Detalle de las 33 Pruebas Automatizadas Ejecutadas

### 2.1. Pruebas Unitarias — `product-publisher-service` (`ProductoServiceUnitTest`: 9/9)
1. **Registro válido:** Registra un producto válido con `publicado = false`, `notificado = false`, `intentosPublicacion = 0`.
2. **Normalización de código:** Convierte código con espacios y minúsculas (`"  prod-abc-123  "`) a formato canónico (`"PROD-ABC-123"`).
3. **Rechazo de código duplicado:** Lanza `ProductoDuplicadoException` y verifica con `verify(productoRepository, never()).save(...)`.
4. **Rechazo de datos inválidos (Bean Validation):** Verifica rechazo de código vacío, nombre corto, descripción corta y precio negativo.
5. **Construcción del mensaje JMS y marcado `publicado = true`:** Verifica el payload `ProductoEventMessage` enviado a `ProductoJmsPublisher` y el cambio de estado a `publicado = true`, `notificado = false`.
6. **Identificación de productos pendientes:** Verifica que el Job (`ProductoPendingPublisherScheduler`) consulta `findByPublicadoFalseOrderByFechaCreacionAsc()` y procesa cada pendiente.
7. **Conservación de `publicado = false` ante fallo del broker:** Verifica que cuando ActiveMQ lanza excepción, el producto mantiene `publicado = false`, `notificado = false` e incrementa `intentosPublicacion = 1`.
8. **Actualización a `notificado = true` con confirmación válida:** Verifica transición a `notificado = true` y asignación de `fechaNotificacion` al recibir `ProductoNotificacionMessage(..., persistido = true)`.
9. **Idempotencia y seguridad ante confirmaciones inválidas o repetidas:** Verifica que una confirmación con `persistido = false` se ignora sin tocar la BD y que una confirmación repetida sobre un producto ya `notificado = true` no sobrescribe `fechaNotificacion` ni re-ejecuta `save(...)`.

### 2.2. Pruebas Unitarias — `product-consumer-service` (`ProductoSincronizacionServiceUnitTest`: 6/6)
1. **Persistencia de producto nuevo y confirmación:** Verifica guardado en `grupoasd_prod.producto` y envío del `ProductoNotificacionMessage` con `persistido = true`.
2. **Detección de existente y prevención de duplicados:** Verifica que ante la recepción repetida del mismo código no se vuelve a invocar `save(...)`, pero se reenvía confirmación idempotente para destrabar al Componente 1.
3. **Manejo idempotente de carrera concurrente (`DataIntegrityViolationException`):** Verifica que si dos hilos intentan insertar el mismo código simultáneamente, se recupera el registro ganador y se confirma sin duplicar.
4. **Construcción exacta de confirmación:** Verifica con `ArgumentCaptor<ProductoNotificacionMessage>` que `idOrigen`, `idDestino`, `codigo` y `persistido = true` coincidan exactamente.
5. **No confirmación ante fallo de persistencia y propagación para reintento:** Verifica que si `save(...)` falla con `DataAccessResourceFailureException`, la excepción se propaga al contenedor JMS y `notificacionJmsPublisher` nunca es invocado.
6. **Rechazo de mensajes nulos o inválidos:** Verifica que mensajes con código vacío o precio negativo lanzan `MensajeProductoInvalidoException` sin tocar la base de datos ni enviar confirmación.

### 2.3. Pruebas de Integración — `product-publisher-service` (`ProductPublisherIntegrationTest`: 9/9)
Ejecutadas contra **PostgreSQL 16 real** (`jdbc:postgresql://localhost:5432/grupoasd_test_db`, esquema `grupoasd_dev`) y **ActiveMQ Classic 6.3.2 real**:
1. `POST /api/v1/productos` válido (`201 Created`) y verificación real en `grupoasd_dev.producto`.
2. Rechazo `400 Bad Request` ante datos inválidos y `409 Conflict` ante código duplicado.
3. Consulta REST de productos (`GET /api/v1/productos`, `GET /api/v1/productos/{codigo}`, `GET /api/v1/productos/pendientes-publicacion`).
4. Identificación de pendientes por el Job (`ProductoPendingPublisherScheduler`) y publicación real en la cola `test.grupoasd.productos.registro.queue` de ActiveMQ.
5. Fallo temporal de publicación en ActiveMQ (`publicado = false`, `intentosPublicacion = 1`) y recuperación exitosa en el siguiente ciclo (`publicado = true`, `intentosPublicacion = 2`).
6. Recepción asíncrona real por `@JmsListener` en `test.grupoasd.productos.notificacion.queue` y actualización de `notificado = true` en PostgreSQL verificada con `Awaitility`.
7. Auto-recuperación de productos con `publicado = true` y `notificado = false` tras reconexión.
8. Rechazo `400 Bad Request` cuando `precio` recibe texto no numérico (`"MIL PESOS"`).
9. Verificación en `INFORMATION_SCHEMA` del esquema `grupoasd_dev` y las 10 columnas de la tabla `producto`.

### 2.4. Pruebas de Integración — `product-consumer-service` (`ProductConsumerIntegrationTest`: 6/6)
Ejecutadas contra **PostgreSQL 16 real** (`jdbc:postgresql://localhost:5432/grupoasd_test_db`, esquema `grupoasd_prod`) y **ActiveMQ Classic 6.3.2 real**:
1. Consumo asíncrono real desde `test.grupoasd.productos.registro.queue`, persistencia en `grupoasd_prod.producto` y publicación de confirmación en `test.grupoasd.productos.notificacion.queue`.
2. Prevención de duplicados en PostgreSQL ante el envío de dos mensajes JMS idénticos (`count() == 1`).
3. Rechazo de mensaje inválido, aplicación de reintentos según `RedeliveryPolicy` (`maximumRedeliveries = 2`) y enrutamiento final a `ActiveMQ.DLQ` sin persistir ni confirmar.
4. Fallo temporal de persistencia en el primer intento JMS (sin enviar confirmación) y recuperación automática en el segundo intento de entrega JMS.
5. Verificación de `RedeliveryPolicy` en `ActiveMQConnectionFactory`.
6. Verificación en `INFORMATION_SCHEMA` del esquema `grupoasd_prod` y las 8 columnas de la tabla `producto`.

### 2.5. Pruebas de Flujo Completo entre Aplicaciones (`FlujoCompletoSincronizacionE2ETest`: 3/3)
1. **Ciclo completo E2E de 10 pasos:**
   - Paso 1-2: `POST /api/v1/productos` persiste en `grupoasd_dev.producto` con `publicado = false`, `notificado = false`.
   - Paso 3-5: El Job detecta el pendiente, publica en ActiveMQ y actualiza `publicado = true`, `notificado = false`.
   - Paso 6-8: El consumidor recibe de ActiveMQ, persiste en `grupoasd_prod.producto` y publica la confirmación en la cola de notificación.
   - Paso 9-10: El `@JmsListener` del publicador consume la confirmación y actualiza `notificado = true` en `grupoasd_dev.producto`.
2. **Idempotencia extremo a extremo:** Entrega duplicada del mismo mensaje sin duplicar filas en `grupoasd_prod.producto` (`COUNT(*) = 1`) y manteniendo `notificado = true` en `grupoasd_dev.producto`.
3. **Recuperación ante fallo temporal de publicación:** Primer ciclo del Job falla (`publicado = false`, `intentosPublicacion = 1`, `0` filas en `grupoasd_prod.producto`); segundo ciclo publica, persiste en `grupoasd_prod.producto` y confirma `notificado = true` en `grupoasd_dev.producto`.

---

## 3. Verificación en Vivo del Entorno Docker Compose (4 Contenedores)

### 3.1. Estado de los Contenedores (`docker compose ps`)
```text
NAME                         IMAGE                                             STATUS                    PORTS
grupoasd-activemq            grupoasd-activemq:6.3.2                           Up (healthy)              127.0.0.1:8161->8161/tcp, 0.0.0.0:61616->61616/tcp
grupoasd-consumer-service    grupoasd-product-sync-product-consumer-service    Up                        0.0.0.0:8081->8081/tcp
grupoasd-postgres            postgres:16-alpine                                Up (healthy)              0.0.0.0:5432->5432/tcp
grupoasd-publisher-service   grupoasd-product-sync-product-publisher-service   Up                        0.0.0.0:8080->8080/tcp
```

### 3.2. Prueba E2E en Vivo entre Contenedores (`PROD-AUDIT-LIVE-01`)

1. **Creación vía API REST en Componente 1 (`POST http://localhost:8080/api/v1/productos`):**
```json
{
    "id": 3,
    "codigo": "PROD-AUDIT-LIVE-01",
    "nombre": "Servidor Empresarial Auditado E2E",
    "descripcion": "Verificacion en vivo entre los 4 contenedores Docker Compose",
    "precio": 28900000,
    "fechaCreacion": "2026-10-28T21:57:16.574009346",
    "publicado": false,
    "notificado": false,
    "fechaPublicacion": null,
    "fechaNotificacion": null,
    "intentosPublicacion": 0
}
```

2. **Estado en Componente 1 tras ejecución del Job y recepción de confirmación (`GET http://localhost:8080/api/v1/productos/PROD-AUDIT-LIVE-01`):**
```json
{
    "id": 3,
    "codigo": "PROD-AUDIT-LIVE-01",
    "nombre": "Servidor Empresarial Auditado E2E",
    "descripcion": "Verificacion en vivo entre los 4 contenedores Docker Compose",
    "precio": 28900000.00,
    "fechaCreacion": "2026-10-28T21:57:16.574009",
    "publicado": true,
    "notificado": true,
    "fechaPublicacion": "2026-10-28T21:57:26.908989",
    "fechaNotificacion": "2026-10-28T21:57:27.438465",
    "intentosPublicacion": 1
}
```

3. **Consulta en Componente 2 (`GET http://localhost:8081/api/v1/productos-destino/PROD-AUDIT-LIVE-01`):**
```json
{
    "id": 3,
    "idOrigen": 3,
    "codigo": "PROD-AUDIT-LIVE-01",
    "nombre": "Servidor Empresarial Auditado E2E",
    "descripcion": "Verificacion en vivo entre los 4 contenedores Docker Compose",
    "precio": 28900000.00,
    "fechaCreacion": "2026-10-28T21:57:16.574009",
    "fechaSincronizacion": "2026-10-28T21:57:27.438465"
}
```

4. **Verificación directa en PostgreSQL (`grupoasd_dev.producto` y `grupoasd_prod.producto`):**
```text
 id |       codigo       | publicado | notificado | intentos_publicacion 
----+--------------------+-----------+------------+----------------------
  3 | PROD-AUDIT-LIVE-01 | t         | t          |                    1
(1 row)

 id | id_origen |       codigo       |   precio    |    fecha_sincronizacion    
----+-----------+--------------------+-------------+----------------------------
  3 |         3 | PROD-AUDIT-LIVE-01 | 28900000.00 | 2026-10-28 21:57:27.438465
(1 row)
```
