#!/usr/bin/env bash
# =====================================================================
# EP3 - Actualiza microservicios ya desplegados en la EC2 (t3.micro).
#   bash ec2-redeploy.sh pedidos envios inventario
# Extrae el nuevo codigo, recompila solo los servicios indicados y los recrea.
# =====================================================================
set -euo pipefail
SERVICES="${*:?Indique los servicios a actualizar}"

tar -xzf ~/pedidos360-ep3.tar.gz -C ~/pedidos360
cd ~/pedidos360
COMPOSE="sudo docker compose -f docker-compose.yml -f docker-compose.lowmem.yml --profile app"
export MAVEN_OPTS="-Xmx384m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"

for service in $SERVICES; do
  echo "== $(date +%T) Compilando $service"
  (cd "$service" && chmod +x mvnw && ./mvnw -q -B -DskipTests package)
done

$COMPOSE up -d --build $SERVICES
for service in $SERVICES; do
  container="pedidos360-$service"
  until sudo docker logs --since 3m "$container" 2>&1 | grep -q "Started "; do sleep 5; done
  echo "== $(date +%T) $container iniciado"
done
echo "== $(date +%T) REDESPLIEGUE COMPLETO"
