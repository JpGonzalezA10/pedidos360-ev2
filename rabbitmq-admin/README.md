# rabbitmq-admin

Microservicio administrador de RabbitMQ de Pedidos360 (EP3). Expone una API REST para crear, consultar y eliminar
colas, exchanges y bindings, inspeccionar mensajes y monitorear las Dead Letter Queues del sistema.

- Puerto local: `8085`
- Ruta externa (API Gateway, solo rol `ADMIN`): `/rabbit-admin/**` -> `/api/rabbit/**`
- Seguridad interna: header `X-API-Key` (`RABBIT_ADMIN_API_KEY`), igual que el resto de microservicios.

## Arquitectura interna

| Capa | Clase | Responsabilidad |
| --- | --- | --- |
| controller | `QueueController`, `ExchangeController`, `BindingController`, `DeadLetterController` | Solo reciben, validan (`@Valid`) y delegan. |
| service | `RabbitAdminService` | Toda la logica: reglas de negocio, `RabbitAdmin` y mapeo a DTO. |
| client | `RabbitManagementClient` | Lectura de la Management HTTP API (listados, estadisticas, inspeccion). |
| config | `RabbitAdminConfig`, `RabbitAdminProperties` | Beans de infraestructura y configuracion centralizada (`rabbit-admin.*`). |
| dto | `QueueCreateRequest`, `ExchangeCreateRequest`, `BindingRequest`, `AdminResponses` | Contratos de entrada validados y de salida. |
| exception | `GlobalExceptionHandler` | Errores JSON consistentes con detalle por campo. |

## Endpoints

| Verbo | Ruta | Descripcion | Respuestas |
| --- | --- | --- | --- |
| GET | `/api/rabbit/queues` | Lista colas (listos, sin ACK, consumidores, argumentos) | 200 |
| GET | `/api/rabbit/queues/{name}` | Detalle de una cola | 200, 404 |
| POST | `/api/rabbit/queues` | Crea cola (durable, TTL, max-length, DLX) | 201, 400, 403, 409, 422 |
| DELETE | `/api/rabbit/queues/{name}?ifUnused&ifEmpty` | Elimina cola, opcionalmente solo si no esta en uso o vacia | 200, 403, 404, 409 |
| GET | `/api/rabbit/queues/{name}/messages?count=` | Inspecciona mensajes sin consumirlos (incluye `x-death`) | 200, 404 |
| DELETE | `/api/rabbit/queues/{name}/messages` | Purga la cola | 200, 404 |
| GET | `/api/rabbit/exchanges` | Lista exchanges | 200 |
| GET | `/api/rabbit/exchanges/{name}` | Detalle de un exchange | 200, 404 |
| POST | `/api/rabbit/exchanges` | Crea exchange `direct`/`topic`/`fanout`/`headers` | 201, 400, 403, 409 |
| DELETE | `/api/rabbit/exchanges/{name}` | Elimina exchange | 200, 403, 404 |
| GET | `/api/rabbit/bindings` | Lista bindings | 200 |
| POST | `/api/rabbit/bindings` | Crea binding exchange -> cola | 201, 400, 404, 409 |
| DELETE | `/api/rabbit/bindings?exchange=&queue=&routingKey=` | Elimina binding | 200, 400, 404 |
| GET | `/api/rabbit/dead-letters` | Resumen de DLQ (`*.dlq`): total, alerta y detalle | 200 |

## Validaciones

- Nombres: 3 a 200 caracteres, inician con letra o numero, solo letras, numeros, `.`, `_` y `-` (nada vacio).
- Prefijos reservados (`amq.`) no se crean ni eliminan: **403**.
- `messageTtlMs` y `maxLength` positivos y con tope; `deadLetterRoutingKey` exige `deadLetterExchange`: **400**.
- El DLX informado debe existir: **422**. Recurso duplicado o en uso: **409**. Recurso inexistente: **404**.
- RabbitMQ no disponible: **503**.

## Ejecucion local

```bash
docker compose up -d rabbitmq          # desde la raiz del monorepo
./mvnw spring-boot:run                 # usuario/clave por defecto: pedidos360 (igual que compose)
```

Swagger UI: `http://localhost:8085/swagger-ui.html`
