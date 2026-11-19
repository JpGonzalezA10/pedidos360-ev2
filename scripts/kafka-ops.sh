#!/usr/bin/env bash
# =====================================================================
# EP5 - Operaciones replicables sobre el cluster Kafka con las herramientas oficiales (CLI),
# ejecutadas dentro del broker pedidos360-kafka-1. Complementa al microservicio kafka-admin.
#
#   bash scripts/kafka-ops.sh <comando> [args]
#
# Topicos
#   topics                                  lista los topicos (sin internos)
#   describe <topico>                       particiones, lider, replicas, ISR y configuracion
#   create <topico> <particiones> [k=v,..]  crea con RF 3 y configuraciones opcionales
#   alter <topico> <k=v,...>                modifica configuraciones (retention.ms, cleanup.policy...)
#   partitions <topico> <total>             aumenta particiones
#   delete <topico>                         elimina el topico
# Consumer groups
#   groups                                  lista los consumer groups
#   group <grupo>                           miembros, asignacion de particiones, offsets y lag
#   lag                                     lag de todos los grupos
#   reset <grupo> <topico> <earliest|latest|offset:N>   reset de offsets (grupo detenido)
# Mensajes
#   produce <topico> [key]                  publica lineas de stdin (con key opcional)
#   consume <topico> [n]                    lee n mensajes desde el inicio mostrando particion/offset/key
# =====================================================================
set -euo pipefail

BROKER=${KAFKA_CONTAINER:-pedidos360-kafka-1}
BOOTSTRAP=${BOOTSTRAP:-kafka-1:29092,kafka-2:29092,kafka-3:29092}
RF=${KAFKA_REPLICATION_FACTOR:-3}

k() { sudo docker exec -i "$BROKER" "$@"; }

cmd=${1:-help}
shift || true
case "$cmd" in
  topics)
    k kafka-topics --bootstrap-server "$BOOTSTRAP" --list --exclude-internal ;;
  describe)
    topic=${1:?topico}
    k kafka-topics --bootstrap-server "$BOOTSTRAP" --describe --topic "$topic"
    echo "-- configuracion propia del topico"
    k kafka-configs --bootstrap-server "$BOOTSTRAP" --describe --entity-type topics --entity-name "$topic" ;;
  create)
    topic=${1:?topico}; partitions=${2:?particiones}; configs=${3:-}
    args=(--bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$topic"
          --partitions "$partitions" --replication-factor "$RF")
    if [ -n "$configs" ]; then
      IFS=',' read -ra pairs <<< "$configs"
      for pair in "${pairs[@]}"; do args+=(--config "$pair"); done
    fi
    k kafka-topics "${args[@]}" ;;
  alter)
    topic=${1:?topico}; configs=${2:?k=v,...}
    k kafka-configs --bootstrap-server "$BOOTSTRAP" --alter --entity-type topics --entity-name "$topic" --add-config "$configs" ;;
  partitions)
    topic=${1:?topico}; total=${2:?total}
    k kafka-topics --bootstrap-server "$BOOTSTRAP" --alter --topic "$topic" --partitions "$total" ;;
  delete)
    topic=${1:?topico}
    k kafka-topics --bootstrap-server "$BOOTSTRAP" --delete --topic "$topic" ;;
  groups)
    k kafka-consumer-groups --bootstrap-server "$BOOTSTRAP" --list ;;
  group)
    group=${1:?grupo}
    k kafka-consumer-groups --bootstrap-server "$BOOTSTRAP" --describe --group "$group" --members --verbose
    k kafka-consumer-groups --bootstrap-server "$BOOTSTRAP" --describe --group "$group" ;;
  lag)
    k kafka-consumer-groups --bootstrap-server "$BOOTSTRAP" --describe --all-groups ;;
  reset)
    group=${1:?grupo}; topic=${2:?topico}; target=${3:?earliest|latest|offset:N}
    case "$target" in
      earliest) how=(--to-earliest) ;;
      latest) how=(--to-latest) ;;
      offset:*) how=(--to-offset "${target#offset:}") ;;
      *) echo "Destino invalido: $target"; exit 1 ;;
    esac
    k kafka-consumer-groups --bootstrap-server "$BOOTSTRAP" --group "$group" --topic "$topic" \
      --reset-offsets "${how[@]}" --execute ;;
  produce)
    topic=${1:?topico}; key=${2:-}
    if [ -n "$key" ]; then
      sed "s/^/$key|/" | k kafka-console-producer --bootstrap-server "$BOOTSTRAP" --topic "$topic" \
        --property parse.key=true --property 'key.separator=|'
    else
      k kafka-console-producer --bootstrap-server "$BOOTSTRAP" --topic "$topic"
    fi ;;
  consume)
    topic=${1:?topico}; n=${2:-10}
    k kafka-console-consumer --bootstrap-server "$BOOTSTRAP" --topic "$topic" --from-beginning \
      --max-messages "$n" --timeout-ms 10000 \
      --property print.partition=true --property print.offset=true --property print.key=true \
      --property print.headers=false 2>/dev/null || true ;;
  *)
    sed -n '2,25p' "$0" ;;
esac
