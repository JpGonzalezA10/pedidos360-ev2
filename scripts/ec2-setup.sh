#!/usr/bin/env bash
# =====================================================================
# EP3 - Prepara una instancia EC2 Amazon Linux 2023 para ejecutar Pedidos360.
# Instala Docker, Docker Compose v2, Buildx, git y jq.
#   bash scripts/ec2-setup.sh
# Luego (en la carpeta del proyecto):
#   docker compose --profile app up -d --build
#   docker exec -i pedidos360-mysql mysql < smartlogix_inventory_seed_products.sql
# =====================================================================
set -euo pipefail

sudo dnf install -y docker git jq util-linux-user
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"

ARCH=$(uname -m)
[ "$ARCH" = "aarch64" ] && BIN_ARCH=arm64 || BIN_ARCH=amd64
PLUGINS=/usr/local/lib/docker/cli-plugins
sudo mkdir -p "$PLUGINS"

# Docker Compose v2 (plugin) y Buildx (requerido por "compose build").
sudo curl -fsSL "https://github.com/docker/compose/releases/download/v2.39.2/docker-compose-linux-${ARCH}" \
  -o "$PLUGINS/docker-compose"
sudo curl -fsSL "https://github.com/docker/buildx/releases/download/v0.26.1/buildx-v0.26.1.linux-${BIN_ARCH}" \
  -o "$PLUGINS/docker-buildx"
sudo chmod +x "$PLUGINS/docker-compose" "$PLUGINS/docker-buildx"

sudo docker version --format 'Docker {{.Server.Version}}'
sudo docker compose version
echo "Listo. Cierre y vuelva a abrir la sesion SSH para usar docker sin sudo."
