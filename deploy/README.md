# Deploy do WMS (servidor da fila-de-conferencia)

Servidor: `ubuntu@163.176.239.42` (mesmo da fila-conf-*). Docker + Docker Compose.
Stack própria (banco, backend Ktor, frontend Angular/nginx), portas **3003** (API)
e **9005** (web) — livres, confirmadas.

## Layout

| Serviço        | Container            | Porta        | O que é                                  |
|----------------|----------------------|--------------|-------------------------------------------|
| `wms-db`       | `wms-db-prod`        | interna 5432 | Postgres 15, volume `wms_pgdata`          |
| `wms-migrate`  | `wms-migrate-prod`   | —            | one-shot: `db/migrations/` + `deploy/seed/` |
| `wms-backend`  | `wms-backend-prod`   | `3003:8080`  | Ktor (`backend/Dockerfile.prod`)          |
| `wms-frontend` | `wms-frontend-prod`  | `9005:80`    | Angular build + nginx (proxy `/api`→backend) |

O `wms-backend` **não roda migração** — quem faz é o `wms-migrate` (roda antes,
`condition: service_completed_successfully`). Migrações versionadas em
`db/migrations/V*.sql`, aplicadas em ordem e registradas em
`public.schema_migrations` (idempotente).

## Primeiro deploy

```bash
ssh ubuntu@163.176.239.42
cd ~/projetos
git clone https://github.com/joao-cavalcante/gestao-de-estoque.git wms
cd wms

# 1) env
cp deploy/env.prod.example .env
nano .env                 # preencher POSTGRES_PASSWORD, WMS_DB_PASSWORD, WMS_JWT_SECRET
                          # WMS_CREDENTIALS_KEY já vem com a chave que cifrou o seed

# 2) seeds (NÃO estão no git — repo é público, seed tem credenciais Sankhya cifradas)
#    copiar da máquina de dev:
#    scp deploy/seed/01-tenants-users.sql deploy/seed/02-balancas.sql \
#        ubuntu@163.176.239.42:~/projetos/wms/deploy/seed/

# 3) subir
docker compose -f docker-compose.prod.yml up -d --build

# 4) conferir
docker compose -f docker-compose.prod.yml logs -f wms-migrate   # deve terminar "concluído."
curl -s localhost:3003/health                                   # {"status":"ok"}
curl -s -o /dev/null -w '%{http_code}\n' localhost:9005          # 200
```

Web: `http://163.176.239.42:9005` — login `super.negri` / senha atual.

## Atualizar (após `git push` novo)

```bash
ssh ubuntu@163.176.239.42
cd ~/projetos/wms && git pull
# APP_BUILD/APP_COMMIT = versão no cabeçalho do front (HMI-OPS · v4.2.<build>); sem eles sai só "v4.2".
export APP_BUILD=$(git rev-list --count HEAD) APP_COMMIT=$(git rev-parse --short HEAD)
docker compose -f docker-compose.prod.yml up -d --build
```

Migrações novas (`V30+`) são aplicadas sozinhas pelo `wms-migrate` no restart.
Seeds usam `ON CONFLICT DO NOTHING` — rodar de novo não duplica nada.

## Notas

- **Balança**: `deploy/seed/02-balancas.sql` é um chute a partir do
  `config.json` do agente local (o banco do fila-conf-negri está vazio).
  Ajuste na tela **Balanças** do WMS se a estação real for diferente.
- **Chave de cripto**: `WMS_CREDENTIALS_KEY` no exemplo é a chave de dev fixa
  do código (as credenciais Sankhya do seed foram cifradas com ela). Pra
  rotacionar: gerar nova chave, decifrar e re-cifrar as credenciais no banco.
- **CORS**: o backend está com `anyHost()`. O nginx serve tudo same-origin
  (proxy `/api`), então na prática não dispara — mas vale restringir depois.

## Staging (pré-produção)

Fluxo de branches: `develop` (desenvolvimento) → `staging` (pré-produção, testada aqui) → `master` (produção).

Mesmo servidor, stack isolada (`docker-compose.staging.yml`, projeto compose `wms-staging`):
clone próprio em `~/projetos/wms-staging` na branch `staging`, banco próprio (volume
`wms-staging_wms_pgdata`), portas **3004** (API, só no localhost do servidor) e **9006** (web, a única aberta pra fora).

- **Sankhya só leitura**: `WMS_SANKHYA_SOMENTE_LEITURA=true` — lê a produção e bloqueia toda gravação
  (finalizar, cortar, faturar, liberar falham de propósito no fim).
- **Senha de acesso**: o nginx pede usuário/senha (`deploy/staging.htpasswd`, só no servidor) pra abrir a página; a `/api` fica só com o login do WMS (o Bearer do app ocupa o mesmo cabeçalho do Basic).
- **Banco**: começa como cópia do banco de produção (dá pra recopiar — ver abaixo).

Web: `http://163.176.239.42:9006`.

### Criar (uma vez)

```bash
cd ~/projetos && git clone -b staging https://github.com/joao-cavalcante/gestao-de-estoque.git wms-staging
cd wms-staging
cp ../wms/deploy/seed/*.sql deploy/seed/
# .env próprio: senhas novas; WMS_CREDENTIALS_KEY igual à da produção (o banco copiado está cifrado com ela)
printf 'staging:%s\n' "$(openssl passwd -apr1 'SENHA')" > deploy/staging.htpasswd
docker compose -f docker-compose.staging.yml up -d wms-db
# depois: copiar o banco de produção (abaixo) e subir o resto
docker compose -f docker-compose.staging.yml up -d --build
```

### Copiar / recopiar o banco de produção

```bash
cd ~/projetos/wms-staging
docker compose -f docker-compose.staging.yml stop wms-backend
docker exec wms-db-staging psql -U postgres -c "drop database if exists wms with (force)" -c "create database wms"
docker exec wms-db-prod pg_dumpall -U postgres --roles-only | docker exec -i wms-db-staging psql -U postgres -q
docker exec wms-db-prod pg_dump -U postgres -d wms | docker exec -i wms-db-staging psql -U postgres -d wms -q
docker compose -f docker-compose.staging.yml up -d   # wms-migrate ajusta a senha do wms_app pro .env do staging
```

### Atualizar

```bash
cd ~/projetos/wms-staging && git pull
export APP_BUILD=$(git rev-list --count HEAD) APP_COMMIT=$(git rev-parse --short HEAD)
docker compose -f docker-compose.staging.yml up -d --build
curl -s localhost:3004/health   # {"status":"ok"}
```
