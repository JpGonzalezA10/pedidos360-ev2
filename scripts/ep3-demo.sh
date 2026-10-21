#!/usr/bin/env bash
# =====================================================================
# EP3 - Demostracion de mensajeria RabbitMQ en Pedidos360.
# Ejecutar en el host donde corre el stack (docker compose --profile app up -d).
#   bash scripts/ep3-demo.sh [escenario]
# Escenarios: topologia | flujo | entrega | dlq-permanente | dlq-transitorio | admin | todos (defecto)
# Requiere: curl, jq. Cada paso imprime el comando y la respuesta real para usar como evidencia.
# =====================================================================
set -uo pipefail

PEDIDOS=${PEDIDOS_URL:-http://localhost:8084}
ENVIOS=${ENVIOS_URL:-http://localhost:8083}
INVENTARIO=${INVENTARIO_URL:-http://localhost:8081}
ADMIN=${ADMIN_URL:-http://localhost:8085}
RABBIT_MGMT=${RABBIT_MGMT_URL:-http://localhost:15672}
RABBIT_USER=${RABBITMQ_USERNAME:-pedidos360}
RABBIT_PASS=${RABBITMQ_PASSWORD:-pedidos360}
PEDIDOS_KEY=${PEDIDOS_API_KEY:-local-dev-orders-key}
ENVIOS_KEY=${ENVIOS_API_KEY:-local-dev-shipping-key}
INVENTARIO_KEY=${INVENTARIO_API_KEY:-local-dev-inventory-key}
ADMIN_KEY=${RABBIT_ADMIN_API_KEY:-local-dev-rabbit-admin-key}
# Cliente demo (UUID fijo) y producto con stock cercano a su punto de reposicion (16 disp., reposicion 4).
CLIENTE_ID=${CLIENTE_ID:-11111111-2222-3333-4444-555555555555}
SKU=${SKU:-SLX-HOG-003}
STATE_FILE=/tmp/ep3-demo-state

title() { printf '\n\033[1;36m==================== %s ====================\033[0m\n' "$1"; }
step()  { printf '\n\033[1;33m$ %s\033[0m\n' "$1"; }
pause() { printf '\033[2m... esperando %ss para que los consumidores procesen ...\033[0m\n' "$1"; sleep "$1"; }

admin()  { curl -s -H "X-API-Key: $ADMIN_KEY" -H 'Content-Type: application/json' "$@"; }
logs()   { docker logs --since "${2:-2m}" "$1" 2>&1 | grep -E "$3" | tail -n "${4:-15}"; }

topologia() {
  title "1. Topologia declarada por los microservicios (via rabbitmq-admin)"
  step "GET $ADMIN/api/rabbit/exchanges"
  admin "$ADMIN/api/rabbit/exchanges" | jq -r '.[] | select(.name|startswith("amq.")|not) | "\(.name)\t\(.type)\tdurable=\(.durable)"' | column -t
  step "GET $ADMIN/api/rabbit/queues"
  admin "$ADMIN/api/rabbit/queues" | jq -r '.[] | "\(.name)\tlistos=\(.messagesReady)\tconsumidores=\(.consumers)\tDLX=\(.arguments["x-dead-letter-exchange"] // "-")\tTTL=\(.arguments["x-message-ttl"] // "-")"' | column -t
  step "GET $ADMIN/api/rabbit/bindings"
  admin "$ADMIN/api/rabbit/bindings" | jq -r '.[] | "\(.exchange) --[\(.routingKey)]--> \(.destination)"'
}

flujo() {
  title "2. Flujo asincrono feliz: pedido.confirmado -> envios + inventario"
  step "POST $PEDIDOS/api/orders  (cliente $CLIENTE_ID compra 12 x $SKU)"
  ORDER=$(curl -s -X POST "$PEDIDOS/api/orders" \
    -H "X-API-Key: $PEDIDOS_KEY" -H "X-User-Id: $CLIENTE_ID" -H 'X-User-Roles: CLIENTE' \
    -H 'Content-Type: application/json' \
    -d "{\"shippingAddress\":\"Av. Concha y Toro 1340, Puente Alto\",\"items\":[{\"sku\":\"$SKU\",\"quantity\":12}]}")
  echo "$ORDER" | jq '{id, status, totalAmount, items: [.items[] | {sku, quantity}]}'
  ORDER_ID=$(echo "$ORDER" | jq -r '.id')
  echo "ORDER_ID=$ORDER_ID" > "$STATE_FILE"
  pause 4
  step "Log pedidos (productor)"
  logs pedidos360-pedidos 1m 'PUBLISH' 3
  step "Log envios (consumidor pedido.confirmado)"
  logs pedidos360-envios 1m 'CONSUMER|Envio .* creado' 4
  step "GET $ENVIOS/api/shipments/order/$ORDER_ID  (despacho creado automaticamente)"
  curl -s -H "X-API-Key: $ENVIOS_KEY" "$ENVIOS/api/shipments/order/$ORDER_ID" | jq '{id, orderId, status, shippingAddress}'
  step "Log inventario (consumidor pedido.confirmado + notificacion stock bajo)"
  logs pedidos360-inventario 1m 'CONSUMER|PUBLISH|NOTIFICACION' 6
}

entrega() {
  title "3. Flujo inverso: envio.estado.actualizado -> pedidos sincroniza el estado"
  # shellcheck disable=SC1090
  source "$STATE_FILE"
  SHIPMENT_ID=$(curl -s -H "X-API-Key: $ENVIOS_KEY" "$ENVIOS/api/shipments/order/$ORDER_ID" | jq -r '.id')
  for STATUS in ready_to_ship in_transit delivered; do
    step "PATCH $ENVIOS/api/shipments/$SHIPMENT_ID/status  -> $STATUS"
    curl -s -X PATCH "$ENVIOS/api/shipments/$SHIPMENT_ID/status" -H "X-API-Key: $ENVIOS_KEY" \
      -H 'Content-Type: application/json' -d "{\"status\":\"$STATUS\",\"location\":\"CD Santiago\"}" | jq -c '{id, status}'
    pause 2
  done
  step "Log pedidos (consumidor envio.estado.actualizado)"
  logs pedidos360-pedidos 1m 'CONSUMER|PUBLISH' 8
  step "GET $PEDIDOS/api/orders/$ORDER_ID  (estado final del pedido)"
  curl -s -H "X-API-Key: $PEDIDOS_KEY" -H "X-User-Id: $CLIENTE_ID" -H 'X-User-Roles: CLIENTE' \
    "$PEDIDOS/api/orders/$ORDER_ID" | jq '{id, status, updatedAt}'
}

dlq_permanente() {
  title "4. Error permanente: mensaje invalido -> NACK sin requeue -> DLQ (sin reintentos)"
  step "Publicar pedido.confirmado SIN orderId ni items en pedidos.events (Management API)"
  curl -s -u "$RABBIT_USER:$RABBIT_PASS" -H 'Content-Type: application/json' \
    -X POST "$RABBIT_MGMT/api/exchanges/%2F/pedidos.events/publish" \
    -d '{"properties":{"content_type":"application/json","message_id":"demo-invalido-001"},"routing_key":"pedido.confirmado","payload":"{\"shippingAddress\":\"sin pedido\"}","payload_encoding":"string"}' | jq -c .
  pause 3
  step "Log envios: NACK -> DLQ"
  logs pedidos360-envios 1m 'DLQ' 3
  step "Log inventario: NACK -> DLQ"
  logs pedidos360-inventario 1m 'DLQ' 3
  step "GET $ADMIN/api/rabbit/dead-letters"
  admin "$ADMIN/api/rabbit/dead-letters" | jq .
}

dlq_transitorio() {
  title "5. Error transitorio: inventario caido -> 3 intentos con cola retry (TTL 5s) -> DLQ"
  step "POST $PEDIDOS/api/orders  (nuevo pedido para despachar)"
  ORDER2=$(curl -s -X POST "$PEDIDOS/api/orders" \
    -H "X-API-Key: $PEDIDOS_KEY" -H "X-User-Id: $CLIENTE_ID" -H 'X-User-Roles: CLIENTE' \
    -H 'Content-Type: application/json' \
    -d '{"shippingAddress":"Los Carrera 501, Concepcion","items":[{"sku":"SLX-ELE-003","quantity":1}]}')
  ORDER2_ID=$(echo "$ORDER2" | jq -r '.id'); echo "pedido $ORDER2_ID -> $(echo "$ORDER2" | jq -r '.status')"
  pause 3
  SHIP2=$(curl -s -H "X-API-Key: $ENVIOS_KEY" "$ENVIOS/api/shipments/order/$ORDER2_ID" | jq -r '.id')
  step "docker stop pedidos360-inventario   (simula caida de la dependencia)"
  docker stop pedidos360-inventario >/dev/null && echo "inventario detenido"
  for STATUS in ready_to_ship in_transit; do
    step "PATCH envio $SHIP2 -> $STATUS"
    curl -s -X PATCH "$ENVIOS/api/shipments/$SHIP2/status" -H "X-API-Key: $ENVIOS_KEY" \
      -H 'Content-Type: application/json' -d "{\"status\":\"$STATUS\"}" | jq -c '{id, status}'
  done
  pause 16
  step "Log pedidos: RETRY 1/3, 2/3 y luego DLQ (reintentos agotados)"
  logs pedidos360-pedidos 1m 'RETRY|DLQ' 6
  step "docker start pedidos360-inventario"
  docker start pedidos360-inventario >/dev/null && echo "inventario iniciado"
  step "GET $ADMIN/api/rabbit/queues/pedidos.envio-estado.dlq/messages  (x-death y payload)"
  admin "$ADMIN/api/rabbit/queues/pedidos.envio-estado.dlq/messages?count=1" \
    | jq '.[0] | {routingKey, motivo: .headers["x-death"][0].reason, colaOrigen: .headers["x-death"][0].queue, reintentos: .headers["x-retry-count"], payload: (.payload|fromjson)}'
}

admin_api() {
  title "6. Microservicio administrador: CRUD y validaciones"
  step "POST /api/rabbit/exchanges  {notificaciones.events, topic}"
  admin -X POST "$ADMIN/api/rabbit/exchanges" -d '{"name":"notificaciones.events","type":"topic","durable":true}' -w '\nHTTP %{http_code}\n'
  step "POST /api/rabbit/exchanges  {notificaciones.dlx, direct}"
  admin -X POST "$ADMIN/api/rabbit/exchanges" -d '{"name":"notificaciones.dlx","type":"direct"}' -w '\nHTTP %{http_code}\n'
  step "POST /api/rabbit/queues  {notificaciones.queue con TTL, max-length y DLX}"
  admin -X POST "$ADMIN/api/rabbit/queues" -d '{"name":"notificaciones.queue","durable":true,"messageTtlMs":60000,"maxLength":1000,"deadLetterExchange":"notificaciones.dlx","deadLetterRoutingKey":"notificaciones.dlq"}' -w '\nHTTP %{http_code}\n'
  step "POST /api/rabbit/bindings  {pedidos.events --pedido.#--> notificaciones.queue}"
  admin -X POST "$ADMIN/api/rabbit/bindings" -d '{"exchange":"pedidos.events","queue":"notificaciones.queue","routingKey":"pedido.#"}' -w '\nHTTP %{http_code}\n'
  step "Validacion: POST /api/rabbit/queues con nombre vacio -> 400"
  admin -X POST "$ADMIN/api/rabbit/queues" -d '{"name":"","messageTtlMs":-1}' -w '\nHTTP %{http_code}\n'
  step "Validacion: tipo de exchange invalido -> 400"
  admin -X POST "$ADMIN/api/rabbit/exchanges" -d '{"name":"x.events","type":"broadcast"}' -w '\nHTTP %{http_code}\n'
  step "Validacion: DLX inexistente -> 422"
  admin -X POST "$ADMIN/api/rabbit/queues" -d '{"name":"otra.queue","deadLetterExchange":"no.existe.dlx"}' -w '\nHTTP %{http_code}\n'
  step "Validacion: cola duplicada -> 409"
  admin -X POST "$ADMIN/api/rabbit/queues" -d '{"name":"notificaciones.queue"}' -w '\nHTTP %{http_code}\n'
  step "Validacion: recurso reservado amq.topic -> 403"
  admin -X DELETE "$ADMIN/api/rabbit/exchanges/amq.topic" -w '\nHTTP %{http_code}\n'
  step "Validacion: eliminar cola con consumidores (ifUnused=true) -> 409"
  admin -X DELETE "$ADMIN/api/rabbit/queues/pedidos.envio-estado.queue?ifUnused=true" -w '\nHTTP %{http_code}\n'
  step "DELETE /api/rabbit/bindings?exchange=pedidos.events&queue=notificaciones.queue&routingKey=pedido.%23"
  admin -X DELETE "$ADMIN/api/rabbit/bindings?exchange=pedidos.events&queue=notificaciones.queue&routingKey=pedido.%23" -w '\nHTTP %{http_code}\n'
  step "DELETE /api/rabbit/queues/notificaciones.queue"
  admin -X DELETE "$ADMIN/api/rabbit/queues/notificaciones.queue" -w '\nHTTP %{http_code}\n'
  step "DELETE /api/rabbit/exchanges/notificaciones.events y notificaciones.dlx"
  admin -X DELETE "$ADMIN/api/rabbit/exchanges/notificaciones.events" -w '\nHTTP %{http_code}\n'
  admin -X DELETE "$ADMIN/api/rabbit/exchanges/notificaciones.dlx" -w '\nHTTP %{http_code}\n'
}

case "${1:-todos}" in
  topologia) topologia ;;
  flujo) flujo ;;
  entrega) entrega ;;
  dlq-permanente) dlq_permanente ;;
  dlq-transitorio) dlq_transitorio ;;
  admin) admin_api ;;
  todos) topologia; flujo; entrega; dlq_permanente; dlq_transitorio; admin_api ;;
  *) echo "Escenario desconocido: $1"; exit 1 ;;
esac
