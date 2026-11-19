# kafka-admin

Microservicio administrador de Apache Kafka de Pedidos360 (EP5). Expone una API REST para inspeccionar el cluster,
crear, modificar y eliminar topicos y sus configuraciones, administrar consumer groups (lag, reset de offsets,
eliminacion) y monitorear/reprocesar las Dead Letter Topics.

- Puerto local: `8086`
- Ruta externa (API Gateway, solo rol `ADMIN`): `/kafka-admin/**` -> `/api/kafka/**`
- Seguridad interna: header `X-API-Key` (`KAFKA_ADMIN_API_KEY`), igual que el resto de microservicios.
- Swagger UI: `http://localhost:8086/swagger-ui.html`

## Arquitectura interna

| Capa | Clase | Responsabilidad |
| --- | --- | --- |
| controller | `TopicController`, `ConsumerGroupController`, `DeadLetterController` | Solo reciben, validan (`@Valid`) y delegan. |
| service | `KafkaAdminService` | Cluster, topicos y consumer groups sobre el `AdminClient`; reglas propias (topicos protegidos, claves permitidas, RF <= brokers). |
| service | `DeadLetterService` | Resumen con alerta, inspeccion y reprocesamiento de `*.DLT`. |
| service | `KafkaAdminOperations` | Espera las operaciones asincronas con timeout y traduce errores de Kafka a 404/409/422/503. |
| config | `KafkaAdminClientConfig`, `KafkaAdminProperties` | `AdminClient` reutilizable y configuracion centralizada (`kafka-admin.*`). |
| dto | `TopicCreateRequest`, `TopicConfigUpdateRequest`, `PartitionIncreaseRequest`, `OffsetResetRequest`, `AdminResponses` | Contratos validados de entrada y de salida. |

## Endpoints

| Verbo | Ruta | Descripcion | Respuestas |
| --- | --- | --- | --- |
| GET | `/api/kafka/cluster` | Cluster id, brokers y controlador | 200, 503 |
| GET | `/api/kafka/topics?includeInternal=false` | Topicos con particiones y factor de replicacion | 200 |
| GET | `/api/kafka/topics/{name}` | Particiones (lider, replicas, ISR, offsets), configuracion y mensajes retenidos | 200, 404 |
| POST | `/api/kafka/topics` | Crea topico (particiones, RF, configs) | 201, 400, 403, 409, 422 |
| PATCH | `/api/kafka/topics/{name}/configs` | Modifica (`set`) o elimina (`delete`) configuraciones | 200, 400, 403, 404, 422 |
| POST | `/api/kafka/topics/{name}/partitions` | Aumenta particiones | 200, 400, 403, 404, 422 |
| DELETE | `/api/kafka/topics/{name}` | Elimina topico | 200, 403, 404 |
| GET | `/api/kafka/consumer-groups` | Consumer groups con estado | 200 |
| GET | `/api/kafka/consumer-groups/{groupId}` | Miembros, particiones asignadas, offsets y lag por particion | 200, 404 |
| POST | `/api/kafka/consumer-groups/{groupId}/reset-offsets` | `EARLIEST`, `LATEST` o `TO_OFFSET` (grupo sin consumidores activos) | 200, 400, 404, 409, 422 |
| DELETE | `/api/kafka/consumer-groups/{groupId}` | Elimina el grupo | 200, 404, 409 |
| GET | `/api/kafka/dead-letters` | Mensajes pendientes por DLT y alerta | 200 |
| GET | `/api/kafka/dead-letters/{topic}/messages?limit=10` | Ultimos mensajes de una DLT con los headers de error | 200, 404, 422 |
| POST | `/api/kafka/dead-letters/{topic}/reprocess?maxMessages=&targetTopic=` | Re-inyecta la DLT en el topico original | 200, 404, 422 |

## Reglas y validaciones

- Topicos internos (`__consumer_offsets`, `_schemas`, `_confluent*`) protegidos: 403.
- Solo se aceptan configuraciones de la lista `kafka-admin.allowed-topic-configs` (retencion, cleanup.policy,
  min.insync.replicas, segmentos, compactacion, tamano de mensaje...): 422 si se envia otra.
- El factor de replicacion no puede superar la cantidad de brokers vivos: 422.
- Kafka solo permite aumentar particiones: 422 si el total no es mayor al actual.
- El reset de offsets exige que el grupo no tenga consumidores activos: 409.
- El reprocesamiento usa el consumer group `kafka-admin-dlt-reprocessor`, por lo que un mensaje no se re-inyecta dos veces.

## Pruebas

```bash
./mvnw test
```

`KafkaAdminServiceIntegrationTest` levanta un broker Kafka embebido (`@EmbeddedKafka`, dentro de la JVM, sin Docker)
y verifica el ciclo completo: crear/modificar/particionar/eliminar topicos, lag y reset de offsets de un grupo,
resumen, inspeccion y reprocesamiento de DLT. `KafkaAdminControllerValidationTest` cubre las validaciones HTTP.
