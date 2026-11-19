#!/usr/bin/env bash
# =====================================================================
# EP5 - Creacion replicable de los topicos de Pedidos360 (idempotente: se puede ejecutar N veces).
# Lo ejecuta el contenedor kafka-init de docker-compose.kafka.yml. Manual:
#   docker exec pedidos360-kafka-1 bash /scripts/create-topics.sh   (si se monta el script)
#
# Topico                         Particiones RF  cleanup  retencion           Uso
# pedidos360.pedidos.eventos     3           3   delete   7 dias / 512 MB      historial del ciclo de vida de pedidos
# pedidos360.inventario.stock    3           3   compact  ultimo valor por SKU "tabla" de stock
# *.DLT                          3           3   delete   14 dias              mensajes fallidos para auditoria/reproceso
# Todos con min.insync.replicas=2 (con acks=all, cada escritura queda en al menos 2 brokers).
# =====================================================================
set -euo pipefail

BOOTSTRAP=${BOOTSTRAP_SERVERS:-kafka-1:29092,kafka-2:29092,kafka-3:29092}
RF=${KAFKA_REPLICATION_FACTOR:-3}
MIN_ISR=${KAFKA_MIN_INSYNC_REPLICAS:-2}

echo "== Esperando que el cluster tenga 3 brokers disponibles"
until [ "$(kafka-broker-api-versions --bootstrap-server "$BOOTSTRAP" 2>/dev/null | grep -c '(id:')" -ge 3 ]; do
  sleep 3
done

# create_topic <nombre> <particiones> <configs separadas por coma>
create_topic() {
  local name=$1 partitions=$2 configs=$3
  kafka-topics --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$name" --partitions "$partitions" --replication-factor "$RF" \
    $(echo "$configs" | tr ',' '\n' | sed 's/^/--config /')
  # Si el topico ya existia, alinea su configuracion con la definida aqui.
  kafka-configs --bootstrap-server "$BOOTSTRAP" --alter --entity-type topics --entity-name "$name" \
    --add-config "$configs" > /dev/null
  echo "   OK $name"
}

echo "== Creando topicos de negocio"
create_topic pedidos360.pedidos.eventos 3 \
  "cleanup.policy=delete,retention.ms=604800000,retention.bytes=536870912,segment.ms=86400000,min.insync.replicas=$MIN_ISR"
create_topic pedidos360.inventario.stock 3 \
  "cleanup.policy=compact,min.compaction.lag.ms=60000,delete.retention.ms=86400000,segment.ms=3600000,min.cleanable.dirty.ratio=0.1,min.insync.replicas=$MIN_ISR"

echo "== Creando Dead Letter Topics"
for source in pedidos360.pedidos.eventos pedidos360.inventario.stock; do
  create_topic "$source.DLT" 3 "cleanup.policy=delete,retention.ms=1209600000,min.insync.replicas=$MIN_ISR"
done

echo "== Topicos del cluster"
kafka-topics --bootstrap-server "$BOOTSTRAP" --describe --topic 'pedidos360.*'
echo "== kafka-init completado"
