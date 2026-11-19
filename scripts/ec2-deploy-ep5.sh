#!/usr/bin/env bash
# =====================================================================
# EP5 - Despliegue de Pedidos360 + Kafka en la EC2. Se ejecuta en la instancia:
#   nohup bash ec2-deploy-ep5.sh > ~/deploy.log 2>&1 &
# Codigo fuente: ~/pedidos360-ep5.tar.gz (si existe) o un git clone en ~/pedidos360
#   git clone -b feature/ep5-kafka https://github.com/JpGonzalezA10/pedidos360-ev2.git ~/pedidos360
# 1. Infraestructura: MySQL, cluster RabbitMQ, ZooKeeper + 3 brokers Kafka, kafka-init y Kafka-UI.
# 2. Compila los 7 microservicios con Maven (uno a la vez) y los levanta con imagenes prebuilt.
# 3. Carga los productos semilla de inventario.
# =====================================================================
set -euo pipefail

until [ -f /var/tmp/bootstrap-done ]; do echo "esperando bootstrap de la instancia..."; sleep 10; done

mkdir -p ~/pedidos360
[ -f ~/pedidos360-ep5.tar.gz ] && tar -xzf ~/pedidos360-ep5.tar.gz -C ~/pedidos360
cd ~/pedidos360
[ -f .env ] || cp .env.example .env
# Listener EXTERNAL de los brokers con la IP publica (IMDSv2).
TOKEN=$(curl -s -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 300')
PUBLIC_IP=$(curl -s -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/public-ipv4)
sed -i "s/^KAFKA_EXTERNAL_HOST=.*/KAFKA_EXTERNAL_HOST=$PUBLIC_IP/" .env
chmod +x scripts/*.sh docker/kafka/*.sh */mvnw

COMPOSE="sudo docker compose -f docker-compose.yml -f docker-compose.prebuilt.yml --profile app"
SERVICES="auth-service inventario pedidos envios rabbitmq-admin kafka-admin api-gateway"

echo "== $(date +%T) Validando archivos docker compose"
$COMPOSE config -q

echo "== $(date +%T) Infraestructura: MySQL + RabbitMQ + ZooKeeper + 3 brokers Kafka + Kafka-UI"
$COMPOSE up -d mysql rabbitmq rabbitmq-2 zookeeper kafka-1 kafka-2 kafka-3 kafka-init kafka-ui

export MAVEN_OPTS="-Xmx512m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
for service in $SERVICES; do
  echo "== $(date +%T) Compilando $service"
  (cd "$service" && ./mvnw -q -B -DskipTests package)
done

echo "== $(date +%T) Esperando kafka-init (creacion de topicos)"
until [ "$(sudo docker inspect -f '{{.State.Status}}' pedidos360-kafka-init 2>/dev/null)" = "exited" ]; do sleep 5; done
sudo docker logs pedidos360-kafka-init | tail -n 30

echo "== $(date +%T) Levantando microservicios"
$COMPOSE up -d --build $SERVICES

until sudo docker logs pedidos360-inventario 2>&1 | grep -q "Started InventarioApplication"; do sleep 10; done
sudo docker exec -i pedidos360-mysql mysql < smartlogix_inventory_seed_products.sql || true
echo "== $(date +%T) Productos semilla cargados"
# Publica la foto inicial de stock en el topico compactado pedidos360.inventario.stock.
sleep 5
curl -s -X POST -H "X-API-Key: $(grep ^INVENTARIO_API_KEY .env | cut -d= -f2)" http://localhost:8081/api/stream/stock/republish || true
echo

for service in $SERVICES; do
  container=pedidos360-$service
  [ "$service" = "api-gateway" ] && container=pedidos360-gateway
  [ "$service" = "auth-service" ] && container=pedidos360-auth
  until sudo docker logs "$container" 2>&1 | grep -q "Started "; do sleep 10; done
  echo "== $(date +%T) $container iniciado"
done

free -m
sudo docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}'
echo "== $(date +%T) DESPLIEGUE COMPLETO  Kafka-UI: http://$PUBLIC_IP:8090"
