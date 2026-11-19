#!/usr/bin/env bash
# =====================================================================
# EP5 - Demostracion de streaming con Apache Kafka en Pedidos360.
# Ejecutar en la EC2 donde corre el stack (docker compose --profile app up -d).
#   bash scripts/ep5-demo.sh [escenario]
# Escenarios: cluster | flujo | orden | grupos | dlt | reintentos | admin | replay | tolerancia | todos
# Requiere curl y jq. Cada paso imprime el comando y la respuesta real para usar como evidencia.
# RabbitMQ (EP3/EP4) sigue funcionando en paralelo: Kafka no reemplaza ninguna integracion previa.
# =====================================================================
set -uo pipefail

PEDIDOS=${PEDIDOS_URL:-http://localhost:8084}
ENVIOS=${ENVIOS_URL:-http://localhost:8083}
INVENTARIO=${INVENTARIO_URL:-http://localhost:8081}
KADMIN=${KAFKA_ADMIN_URL:-http://localhost:8086}
PEDIDOS_KEY=${PEDIDOS_API_KEY:-local-dev-orders-key}
ENVIOS_KEY=${ENVIOS_API_KEY:-local-dev-shipping-key}
INVENTARIO_KEY=${INVENTARIO_API_KEY:-local-dev-inventory-key}
KADMIN_KEY=${KAFKA_ADMIN_API_KEY:-local-dev-kafka-admin-key}
CLIENTE_ID=${CLIENTE_ID:-11111111-2222-3333-4444-555555555555}
SKU=${SKU:-SLX-ELE-002}
TOPIC_PEDIDOS=pedidos360.pedidos.eventos
TOPIC_STOCK=pedidos360.inventario.stock
STATE_FILE=/tmp/ep5-demo-state
OPS="bash $(dirname "$0")/kafka-ops.sh"

title() { printf '\n\033[1;36m==================== %s ====================\033[0m\n' "$1"; }
step()  { printf '\n\033[1;33m$ %s\033[0m\n' "$1"; }
pause() { printf '\033[2m... esperando %ss ...\033[0m\n' "$1"; sleep "$1"; }

kadmin() { curl -s -H "X-API-Key: $KADMIN_KEY" -H 'Content-Type: application/json' "$@"; }
pedidos() { curl -s -H "X-API-Key: $PEDIDOS_KEY" -H "X-User-Id: $CLIENTE_ID" -H 'X-User-Roles: ADMIN' \
  -H 'Content-Type: application/json' "$@"; }
logs() { sudo docker logs --since "${2:-2m}" "$1" 2>&1 | grep -E "$3" | tail -n "${4:-15}" | cut -c1-260; }

cluster() {
  title "1. Cluster Kafka: 3 brokers + ZooKeeper, topicos con particiones, RF 3 e ISR"
  step "sudo docker ps (zookeeper, brokers, kafka-ui, microservicios)"
  sudo docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' | grep -E 'NAMES|zookeeper|kafka|pedidos|envios|inventario|rabbit'
  step "GET $KADMIN/api/kafka/cluster"
  kadmin "$KADMIN/api/kafka/cluster" | jq .
  step "GET $KADMIN/api/kafka/topics"
  kadmin "$KADMIN/api/kafka/topics" | jq -r '.[] | "\(.name)\tparticiones=\(.partitions)\tRF=\(.replicationFactor)"' | column -t
  for topic in $TOPIC_PEDIDOS $TOPIC_STOCK; do
    step "GET $KADMIN/api/kafka/topics/$topic"
    kadmin "$KADMIN/api/kafka/topics/$topic" | jq '{name, replicationFactor, configs, totalMessages,
      partitions: [.partitions[] | {partition, leader, replicas, inSyncReplicas, endOffset}]}'
  done
}

flujo() {
  title "2. Flujo end-to-end: pedido -> Kafka -> 3 consumer groups (+ RabbitMQ en paralelo)"
  step "POST $PEDIDOS/api/orders  (cliente $CLIENTE_ID compra 2 x $SKU)"
  ORDER=$(pedidos -X POST "$PEDIDOS/api/orders" \
    -d "{\"shippingAddress\":\"Av. Concha y Toro 1340, Puente Alto\",\"items\":[{\"sku\":\"$SKU\",\"quantity\":2}]}")
  echo "$ORDER" | jq '{id, status, totalAmount, items: [.items[] | {sku, quantity}]}'
  ORDER_ID=$(echo "$ORDER" | jq -r '.id')
  echo "ORDER_ID=$ORDER_ID" > "$STATE_FILE"
  pause 5
  step "Productor pedidos -> $TOPIC_PEDIDOS (y RabbitMQ pedidos.events, EP3)"
  logs pedidos360-pedidos 1m 'KAFKA-PUBLISH|\[PUBLISH\]' 4
  step "Productor inventario -> $TOPIC_STOCK (stock reservado)"
  logs pedidos360-inventario 1m 'KAFKA-PUBLISH' 3
  step "Consumer group envios-seguimiento"
  logs pedidos360-envios 1m 'KAFKA-CONSUME|TRACKING' 3
  step "GET $ENVIOS/api/stream/orders/$ORDER_ID/timeline"
  curl -s -H "X-API-Key: $ENVIOS_KEY" "$ENVIOS/api/stream/orders/$ORDER_ID/timeline" | jq .
  step "Consumer group inventario-demanda"
  logs pedidos360-inventario 1m 'KAFKA-CONSUME|DEMAND' 3
  step "GET $INVENTARIO/api/stream/demand"
  curl -s -H "X-API-Key: $INVENTARIO_KEY" "$INVENTARIO/api/stream/demand" | jq .
  step "Consumer group pedidos-stock-cache"
  logs pedidos360-pedidos 1m 'KAFKA-CONSUME|STOCK-PROJECTION' 3
  step "GET $PEDIDOS/api/stream/stock/$SKU"
  pedidos "$PEDIDOS/api/stream/stock/$SKU" | jq .
}

orden() {
  title "3. Orden por key: todos los eventos de un pedido van a la MISMA particion"
  # shellcheck disable=SC1090
  source "$STATE_FILE"
  step "PATCH $PEDIDOS/api/orders/$ORDER_ID/status -> CANCELLED"
  pedidos -X PATCH "$PEDIDOS/api/orders/$ORDER_ID/status" -d '{"status":"CANCELLED"}' | jq '{id, status}'
  pause 5
  step "kafka-console-consumer $TOPIC_PEDIDOS (particion, offset, key=orderId)"
  $OPS consume $TOPIC_PEDIDOS 50 | grep "$ORDER_ID" | cut -c1-200
  step "GET $ENVIOS/api/stream/orders/$ORDER_ID/timeline  (CONFIRMED -> CANCELLED en orden)"
  curl -s -H "X-API-Key: $ENVIOS_KEY" "$ENVIOS/api/stream/orders/$ORDER_ID/timeline" | jq .
  step "GET $INVENTARIO/api/stream/demand  (unitsCancelled descontadas)"
  curl -s -H "X-API-Key: $INVENTARIO_KEY" "$INVENTARIO/api/stream/demand" | jq .
}

grupos() {
  title "4. Consumer groups: reparto de particiones (balanceo) y lectura independiente (pub/sub)"
  step "GET $KADMIN/api/kafka/consumer-groups"
  kadmin "$KADMIN/api/kafka/consumer-groups" | jq -r '.[] | "\(.groupId)\t\(.state)\t\(.type)"' | column -t
  for group in envios-seguimiento inventario-demanda pedidos-stock-cache; do
    step "GET $KADMIN/api/kafka/consumer-groups/$group"
    kadmin "$KADMIN/api/kafka/consumer-groups/$group" | jq '{groupId, state, partitionAssignor, totalLag,
      members: [.members[] | {clientId, assignedPartitions}], offsets}'
  done
  step "kafka-consumer-groups --describe --all-groups (CLI)"
  $OPS lag
}

dlt() {
  title "5. Errores permanentes -> Dead Letter Topic (sin bloquear el consumidor)"
  step "Publicar JSON corrupto y un evento sin orderId en $TOPIC_PEDIDOS"
  echo '{esto no es json' | $OPS produce $TOPIC_PEDIDOS demo-corrupto
  echo '{"schemaVersion":1,"eventId":"7d1f0c2a-1111-4c1e-9a1a-000000000001","eventType":"ORDER_CONFIRMED","newStatus":"CONFIRMED","items":[],"occurredAt":"2026-11-18T10:00:00Z"}' \
    | $OPS produce $TOPIC_PEDIDOS demo-sin-orderid
  pause 5
  step "Log envios: [KAFKA-FALLO] + [DLT]"
  logs pedidos360-envios 1m 'KAFKA-FALLO|\[DLT\]' 4
  step "Log inventario: [KAFKA-FALLO] + [DLT]"
  logs pedidos360-inventario 1m 'KAFKA-FALLO|\[DLT\]' 4
  step "GET $KADMIN/api/kafka/dead-letters"
  kadmin "$KADMIN/api/kafka/dead-letters" | jq .
  step "GET $KADMIN/api/kafka/dead-letters/$TOPIC_PEDIDOS.DLT/messages?limit=4"
  kadmin "$KADMIN/api/kafka/dead-letters/$TOPIC_PEDIDOS.DLT/messages?limit=4" | jq '[.[] | {partition, offset, key, value,
    origen: .headers["kafka_dlt-original-topic"], error: .headers["kafka_dlt-exception-message"]}]'
}

reintentos() {
  title "6. Error transitorio: BD caida -> reintentos con backoff 1s/2s/4s -> se recupera"
  ORDER_ID=$(cat /proc/sys/kernel/random/uuid)
  step "sudo docker stop pedidos360-mysql"
  sudo docker stop pedidos360-mysql > /dev/null && echo "MySQL detenido"
  step "Publicar ORDER_CONFIRMED valido para $ORDER_ID (inventario debe validar el SKU en su BD)"
  echo "{\"schemaVersion\":1,\"eventId\":\"$(cat /proc/sys/kernel/random/uuid)\",\"eventType\":\"ORDER_CONFIRMED\",\"orderId\":\"$ORDER_ID\",\"newStatus\":\"CONFIRMED\",\"items\":[{\"sku\":\"$SKU\",\"quantity\":1}],\"source\":\"DEMO\",\"occurredAt\":\"2026-11-18T10:00:00Z\"}" \
    | $OPS produce $TOPIC_PEDIDOS "$ORDER_ID"
  pause 10
  step "sudo docker start pedidos360-mysql"
  sudo docker start pedidos360-mysql > /dev/null && echo "MySQL iniciado"
  pause 25
  step "Log inventario: intentos fallidos y procesamiento exitoso tras la recuperacion"
  logs pedidos360-inventario 2m "KAFKA-FALLO|$ORDER_ID|DLT" 8
}

admin() {
  title "7. Microservicio kafka-admin: crear, modificar, particionar y eliminar topicos"
  local topic=pedidos360.demo.auditoria
  step "POST $KADMIN/api/kafka/topics"
  kadmin -X POST "$KADMIN/api/kafka/topics" -d "{\"name\":\"$topic\",\"partitions\":3,\"replicationFactor\":3,
    \"configs\":{\"cleanup.policy\":\"delete\",\"retention.ms\":\"86400000\",\"min.insync.replicas\":\"2\"}}" \
    | jq '{name, replicationFactor, configs, partitions: [.partitions[] | {partition, leader, replicas}]}'
  step "PATCH $KADMIN/api/kafka/topics/$topic/configs  (retencion 3 dias, max.message.bytes)"
  kadmin -X PATCH "$KADMIN/api/kafka/topics/$topic/configs" \
    -d '{"set":{"retention.ms":"259200000","max.message.bytes":"2097152"}}' | jq '{name, configs}'
  step "POST $KADMIN/api/kafka/topics/$topic/partitions  (3 -> 6)"
  kadmin -X POST "$KADMIN/api/kafka/topics/$topic/partitions" -d '{"totalPartitions":6}' \
    | jq '{name, particiones: (.partitions | length)}'
  step "Validaciones: topico interno protegido, RF > brokers, configuracion no permitida"
  kadmin -X DELETE "$KADMIN/api/kafka/topics/__consumer_offsets" | jq -c '{status, message}'
  kadmin -X POST "$KADMIN/api/kafka/topics" -d '{"name":"demo.rf","partitions":1,"replicationFactor":5}' | jq -c '{status, message}'
  kadmin -X PATCH "$KADMIN/api/kafka/topics/$topic/configs" -d '{"set":{"log.dirs":"/tmp"}}' | jq -c '{status, message}'
  step "DELETE $KADMIN/api/kafka/topics/$topic"
  kadmin -X DELETE "$KADMIN/api/kafka/topics/$topic" | jq .
}

replay() {
  title "8. Replay: reset de offsets del grupo envios-seguimiento a EARLIEST"
  step "sudo docker stop pedidos360-envios  (el grupo debe quedar sin consumidores)"
  sudo docker stop pedidos360-envios > /dev/null && echo "envios detenido"
  pause 5
  step "POST $KADMIN/api/kafka/consumer-groups/envios-seguimiento/reset-offsets  (EARLIEST)"
  kadmin -X POST "$KADMIN/api/kafka/consumer-groups/envios-seguimiento/reset-offsets" \
    -d "{\"topic\":\"$TOPIC_PEDIDOS\",\"strategy\":\"EARLIEST\"}" | jq '{groupId, state, totalLag, offsets}'
  step "sudo docker start pedidos360-envios  (relee todo el historial retenido y reconstruye su proyeccion)"
  sudo docker start pedidos360-envios > /dev/null && echo "envios iniciado"
  pause 40
  logs pedidos360-envios 2m 'KAFKA-CONSUME' 6
  step "GET $ENVIOS/api/stream/orders/dashboard"
  curl -s -H "X-API-Key: $ENVIOS_KEY" "$ENVIOS/api/stream/orders/dashboard" | jq .
  step "POST $KADMIN/api/kafka/dead-letters/$TOPIC_PEDIDOS.DLT/reprocess  (re-inyecta la DLT al topico original)"
  kadmin -X POST "$KADMIN/api/kafka/dead-letters/$TOPIC_PEDIDOS.DLT/reprocess?maxMessages=10" | jq .
}

tolerancia() {
  title "9. Tolerancia a fallos: cae 1 broker, el cluster sigue aceptando escrituras (RF 3, min ISR 2)"
  step "sudo docker stop pedidos360-kafka-2"
  sudo docker stop pedidos360-kafka-2 > /dev/null && echo "broker 2 detenido"
  pause 15
  step "kafka-topics --describe $TOPIC_PEDIDOS  (ISR sin el broker 2, lideres reasignados)"
  $OPS describe $TOPIC_PEDIDOS | grep -E 'Partition:|Topic:' | head -4
  step "POST $PEDIDOS/api/orders con un broker caido (acks=all sigue OK con 2 ISR)"
  pedidos -X POST "$PEDIDOS/api/orders" \
    -d "{\"shippingAddress\":\"Av. Vicuna Mackenna 4860, Macul\",\"items\":[{\"sku\":\"$SKU\",\"quantity\":1}]}" | jq '{id, status}'
  pause 5
  logs pedidos360-pedidos 1m 'KAFKA-PUBLISH' 2
  step "sudo docker start pedidos360-kafka-2"
  sudo docker start pedidos360-kafka-2 > /dev/null && echo "broker 2 iniciado"
  pause 30
  $OPS describe $TOPIC_PEDIDOS | grep -E 'Partition:' | head -3
}

case "${1:-todos}" in
  cluster) cluster ;;
  flujo) flujo ;;
  orden) orden ;;
  grupos) grupos ;;
  dlt) dlt ;;
  reintentos) reintentos ;;
  admin) admin ;;
  replay) replay ;;
  tolerancia) tolerancia ;;
  todos) cluster; flujo; orden; grupos; dlt; reintentos; admin; replay; tolerancia ;;
  *) sed -n '2,9p' "$0" ;;
esac
