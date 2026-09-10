#!/usr/bin/env bash
# Aplica as migrações SQL de db/migrations/ na ordem de versão (V1, V2, ... V10, V11...)
# e roda os seeds de deploy/seed/. Idempotente: cada versão só roda uma vez
# (registrada em public.schema_migrations). Roda como o superusuário do
# Postgres — mesma forma que o ambiente local aplica (docker exec psql -U postgres).
#
# Espera PGHOST/PGUSER/PGPASSWORD/PGDATABASE no ambiente (ver docker-compose.prod.yml).
# WMS_DB_PASSWORD (opcional): se definido, ajusta a senha do role de runtime wms_app.
set -euo pipefail

PSQL="psql -v ON_ERROR_STOP=1 --no-psqlrc -X -q"

echo "[migrate] aguardando Postgres em ${PGHOST}..."
until $PSQL -c 'select 1' >/dev/null 2>&1; do sleep 1; done

$PSQL -c "create table if not exists public.schema_migrations (
  version text primary key,
  applied_at timestamptz not null default now()
);"

aplicadas=0
for f in $(ls /migrations/V*.sql | sort -V); do
  v="$(basename "$f" | sed -E 's/__.*//')"
  ja="$($PSQL -tA -c "select 1 from public.schema_migrations where version = '${v}'")"
  if [ -n "$ja" ]; then
    echo "[migrate] $v — já aplicada, pulando"
    continue
  fi
  echo "[migrate] $v — aplicando..."
  $PSQL -f "$f"
  $PSQL -c "insert into public.schema_migrations (version) values ('${v}')"
  aplicadas=$((aplicadas + 1))
done
echo "[migrate] ${aplicadas} migração(ões) aplicada(s)"

# Senha real do role de runtime (V1 cria wms_app com uma senha placeholder).
if [ -n "${WMS_DB_PASSWORD:-}" ]; then
  echo "[migrate] ajustando senha do role wms_app"
  $PSQL -c "alter role wms_app with password '${WMS_DB_PASSWORD}'"
fi

# Seeds — tenants/usuários preservados + balanças. Idempotentes (ON CONFLICT DO NOTHING).
if ls /seed/*.sql >/dev/null 2>&1; then
  for f in $(ls /seed/*.sql | sort -V); do
    echo "[migrate] seed $(basename "$f")"
    $PSQL -f "$f"
  done
else
  echo "[migrate] nenhum seed em /seed/"
fi

echo "[migrate] concluído."
