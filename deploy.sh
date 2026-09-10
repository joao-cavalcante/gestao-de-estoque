#!/usr/bin/env bash
# deploy.sh — WMS. Atualiza a stack de produção no servidor.
# Uso: ./deploy.sh            (git pull + rebuild + up -d)
#      ./deploy.sh logs       (segue os logs)
set -euo pipefail

SERVER="ubuntu@163.176.239.42"
KEY="${WMS_DEPLOY_KEY:-$HOME/.ssh/fila-conferencia.ppk}"
REMOTE="~/projetos/wms"
COMPOSE="docker compose -f docker-compose.prod.yml"

PLINK="/c/Program Files/PuTTY/plink.exe"
ssh_run() {
  if [ -x "$PLINK" ]; then "$PLINK" -ssh -batch -i "$KEY" "$SERVER" "$@"; else ssh "$SERVER" "$@"; fi
}

case "${1:-deploy}" in
  logs)
    ssh_run "cd $REMOTE && $COMPOSE logs -f --tail=100"
    ;;
  deploy)
    ssh_run "set -e; cd $REMOTE && git pull && $COMPOSE up -d --build && sleep 4 && $COMPOSE ps"
    echo "Deploy OK — http://163.176.239.42:9005"
    ;;
  *)
    echo "Uso: ./deploy.sh [deploy|logs]"; exit 1 ;;
esac
