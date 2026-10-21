#!/usr/bin/env bash
# =====================================================================
# EP3 - Despliegue en EC2 t3.micro (capa gratuita). Se ejecuta en la instancia:
#   nohup bash ec2-deploy-lowmem.sh > ~/deploy.log 2>&1 &
# Compila cada microservicio con Maven (memoria acotada) y levanta el stack con
# docker-compose.lowmem.yml. Luego carga los productos semilla de inventario.
# =====================================================================
set -euo pipefail

until [ -f /var/tmp/bootstrap-done ]; do echo "esperando bootstrap de la instancia..."; sleep 10; done

mkdir -p ~/pedidos360
tar -xzf ~/pedidos360-ep3.tar.gz -C ~/pedidos360
cd ~/pedidos360
[ -f .env ] || cp .env.example .env

COMPOSE="sudo docker compose -f docker-compose.yml -f docker-compose.lowmem.yml --profile app"
SERVICES="inventario pedidos envios rabbitmq-admin"

echo "== $(date +%T) Infraestructura: MySQL + RabbitMQ"
$COMPOSE up -d mysql rabbitmq

# Maven con heap acotado para no agotar la RAM de la t3.micro.
export MAVEN_OPTS="-Xmx384m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
for service in $SERVICES; do
  echo "== $(date +%T) Compilando $service"
  (cd "$service" && chmod +x mvnw && ./mvnw -q -B -DskipTests package)
done

echo "== $(date +%T) Levantando microservicios: $SERVICES"
$COMPOSE up -d --build $SERVICES

echo "== $(date +%T) Esperando que inventario aplique Flyway"
until sudo docker logs pedidos360-inventario 2>&1 | grep -q "Started InventarioApplication"; do sleep 10; done
sudo docker exec -i pedidos360-mysql mysql < smartlogix_inventory_seed_products.sql
echo "== $(date +%T) Productos semilla cargados"

for container in pedidos360-pedidos pedidos360-envios pedidos360-rabbitmq-admin; do
  until sudo docker logs "$container" 2>&1 | grep -q "Started "; do sleep 10; done
  echo "== $(date +%T) $container iniciado"
done

free -m
sudo docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}'
echo "== $(date +%T) DESPLIEGUE COMPLETO"
