# Schema de banco — fundação multi-tenant

## Modelo

Um Postgres compartilhado, isolamento por `tenant_id` reforçado com **Row-Level
Security (RLS)** — não só filtro na aplicação. Tenants `tier='dedicated'`
(enterprise) usam uma `dedicated_db_url` própria; o roteamento pra lá é feito
na aplicação, o schema é o mesmo.

Dois schemas Postgres:

- **`tenancy`** — plano de controle: `tenants`, `erp_connections`,
  `master_users`. Não leva `tenant_id` (são as tabelas que definem os
  próprios tenants).
- **`app`** — plano de dados de negócio: toda tabela aqui tem `tenant_id` e
  RLS habilitada.

## Roles

- **`wms_owner`** — dono das tabelas, roda migração. Nunca é o role de
  conexão da aplicação.
- **`wms_app`** — role de runtime (Ktor/HikariCP conecta como este). Sujeito
  integral à RLS.

Antes de produção: trocar a senha hardcoded de `wms_app` em `V1` (está como
`CHANGE_ME_EM_PRODUCAO` de propósito, pra não escapar sem querer).

**Nunca conectar como superusuário (`postgres`) fora de debug local manual.**
`docker exec wms-db psql -U postgres ...` ignora a RLS por completo — é
exatamente o tipo de acesso que, se vazar pra uma ferramenta/rotina que
rode contra produção, derruba o isolamento entre todos os tenants de uma
vez. Uso aceitável: você, no terminal, investigando algo pontual. Uso
NUNCA aceitável: script, cron, endpoint de admin, ou qualquer coisa que
rode fora de uma sessão manual de debug.

## Convenção de tenant por transação

A aplicação, no início de **cada transação** (não da conexão inteira — importa
pra funcionar com PgBouncer em modo *transaction pooling*):

```sql
SET LOCAL app.tenant_id = '<uuid-do-tenant>';
```

Toda política de RLS lê isso via `tenancy.current_tenant_id()` (função em
`V1`). Sem essa variável setada, a política nega tudo — é fail-closed por
design: esquecer de setar o tenant faz a query não ver nada, não vazar tudo.

## Receita pra toda tabela nova em `app.*`

Ver `V2__app_users_rls_pattern.sql` como exemplo completo. Resumo:

1. `tenant_id uuid not null references tenancy.tenants(id) on delete cascade`
2. Índice com `tenant_id` como **primeira** coluna (isolamento + performance)
3. Toda `UNIQUE` de negócio é composta com `tenant_id` — nunca uma chave
   única "global" (foi exatamente isso que causou o bug de colisão de sessão
   entre tenants no sistema anterior)
4. `ENABLE ROW LEVEL SECURITY`
5. `FORCE ROW LEVEL SECURITY` — sem isso, se algum dia uma query rodar como
   `wms_owner` (dono), a política é ignorada
6. Política `USING`/`WITH CHECK` contra `tenancy.current_tenant_id()`
7. `GRANT ... TO wms_app` — nunca `PUBLIC`

## Tier dedicado

`tenancy.tenants.tier = 'dedicated'` exige `dedicated_db_url` preenchida
(constraint `dedicated_precisa_de_url`). Esse é o único ponto de bifurcação:
a aplicação, ao abrir conexão pra um tenant, decide pool compartilhado vs.
`dedicated_db_url` — o resto do schema/lógica é idêntico nos dois casos.

## Credenciais de ERP (client_id/client_secret/x-token)

`tenancy.erp_connections.credenciais` guarda o JSON de credenciais **cifrado
em repouso**. Duas implementações por trás de uma interface comum
(`SecretsCipher`), escolhidas automaticamente em runtime por
`CredentialsCipher` (backend/tenancy):

- **`LocalAesCipher`** — AES-256-GCM com chave de `WMS_CREDENTIALS_KEY` (env
  var, base64 de 32 bytes). Só pra dev local. A chave mora na própria
  aplicação — qualquer processo com essa env var decifra tudo, sem
  auditoria, sem revogação, sem rotação incremental.
- **`VaultTransitCipher`** — HashiCorp Vault, Transit secrets engine
  ("encryption as a service"). Ativa quando `VAULT_ADDR` + `VAULT_TOKEN`
  estão definidas. A chave real **nunca sai do Vault**: a aplicação manda
  texto plano numa chamada HTTP autenticada e recebe só o ciphertext
  (`vault:v1:...`, autodescritivo). Comprometer o servidor da aplicação dá
  acesso só a ciphertext + um token revogável com TTL — não à chave.

Por que não pgcrypto direto no Postgres nas duas abordagens: a chave (ou o
token de acesso a ela) precisaria passar como parâmetro em toda query, o
que aumenta a superfície de exposição (log de query, `pg_stat_statements`).
Cifrando na aplicação, o banco nunca vê nem a chave nem o texto plano — só
o blob final.

**Testado de verdade contra um Vault real** (não simulado): subi um
container `hashicorp/vault` em modo dev, habilitei o Transit engine, migrei
as credenciais de Negri/Modial pra esse esquema, e confirmei no Postgres
que o valor bruto virou `vault:v1:...` — não mais o blob do AES local nem
o JSON original. Depois **derrubei o Vault de propósito**: a API passou a
responder `credenciaisConfiguradas: false` (decrypt falha sem o Vault no
ar) sem o backend cair — prova concreta de que a chave de fato não mora na
aplicação. Religando o Vault (e recriando a engine/chave — ver nota abaixo
sobre modo dev), tudo voltou a decifrar normalmente.

**Nota sobre modo dev do Vault**: o container usado no teste roda com
armazenamento **em memória** — parar/reiniciar o container perde a chave
transit inteira, não é "só desselar de novo". Isso é uma particularidade do
modo dev (`vault server -dev`), não do produto: um Vault de produção usa
um backend de armazenamento persistente (Raft integrado, Consul, etc.),
sobrevive a um restart do processo e só precisa ser desselado (ou destravar
via auto-unseal com KMS de nuvem) pra voltar a operar — a chave em si
continua lá.

Regras de negócio já implementadas em `TenantRepository`, válidas para
qualquer uma das duas implementações de `SecretsCipher`:

- Nenhuma rota de API decifra e devolve o segredo. `GET`/listagem só
  retornam `credenciaisConfiguradas: boolean` — nunca o valor.
- Atualizar uma conexão de ERP sem reenviar `credenciais` (campo `null`)
  **preserva** o blob cifrado existente — não apaga nem sobrescreve com
  vazio. Só um `credenciais` não-nulo de fato substitui o segredo (rotação).
- Testado ponta a ponta (não só no papel): editar via tela sem tocar nos
  campos de credencial mantém o ciphertext byte-a-byte idêntico no banco;
  reenviar os três campos troca o ciphertext de fato.

### Rodando com Vault localmente

```bash
docker run -d --name wms-vault --cap-add=IPC_LOCK \
  -e 'VAULT_DEV_ROOT_TOKEN_ID=wms-dev-root-token' -p 8200:8200 hashicorp/vault:1.18

curl -H "X-Vault-Token: wms-dev-root-token" -X POST http://localhost:8200/v1/sys/mounts/transit -d '{"type":"transit"}'
curl -H "X-Vault-Token: wms-dev-root-token" -X POST http://localhost:8200/v1/transit/keys/wms-credentials

export VAULT_ADDR="http://localhost:8200"
export VAULT_TOKEN="wms-dev-root-token"
export VAULT_TRANSIT_KEY="wms-credentials"   # opcional, é o default
```

### Antes de produção real

- Trocar o modo dev por um Vault de verdade (storage persistente, TLS,
  unseal via KMS de nuvem ou Shamir com quorum de operadores).
- Token de acesso da aplicação: uma AppRole ou identidade de workload
  (Kubernetes auth, AWS IAM auth) com política restrita a
  `encrypt`/`decrypt` só na chave `wms-credentials` — nunca o root token.
- Rotação periódica: `vault write -f transit/keys/wms-credentials/rotate`
  não exige re-cifrar nada existente (Vault mantém versões antigas pra
  decifrar o que já existe, `min_decryption_version` controla até onde).
- Alternativas equivalentes caso prefiram nuvem gerenciada em vez de Vault
  self-hosted: AWS KMS, GCP Cloud KMS, Azure Key Vault — a interface
  `SecretsCipher` já foi desenhada pra isso ser só mais uma implementação
  nova, sem tocar em `TenantRepository`.

## Autenticação Sankhya

`backend/src/main/kotlin/wms/backend/erp/SankhyaAuthService.kt` implementa
o fluxo OAuth2 `client_credentials` do Sankhya — contrato do próprio
fornecedor (`POST {baseUrl}/authenticate`, form-urlencoded, header
`X-Token` extra além de `client_id`/`client_secret`), não algo inventado
aqui.

- Token cacheado em memória **por tenant**, com margem de renovação de 5
  minutos antes da expiração real.
- Um `Mutex` por tenant evita "estouro de manada" (várias requisições
  batendo no Sankhya ao mesmo tempo quando o token expira) sem serializar
  tenants diferentes entre si.
- Retry com backoff exponencial (3 tentativas) em falha de rede/HTTP.
- Credenciais só existem decifradas pelo tempo da chamada
  (`TenantRepository.obterCredenciaisErp`) — nunca cacheadas em texto
  plano, nunca logadas.
- Rota de teste (não devolve o token, só confirma autenticação):
  `POST /api/tenants/{slug}/erp-connections/sankhya/testar-autenticacao`

**Testado de verdade** contra um mock HTTP local do endpoint `/authenticate`
(não temos credenciais reais do Sankhya neste ambiente): confirmado que a
segunda/terceira chamada em sequência reaproveitam o cache (mock só recebe
1 requisição, não 3); que uma falha temporária (503 simulado) é resolvida
por retry sem expor erro ao chamador; e que esgotar todas as tentativas
retorna `502` com mensagem clara, sem o processo cair.

## Próximos passos (ainda não feitos aqui)

- Migrations das tabelas de domínio (sessão de conferência, volume,
  balança, cache de produto etc.) seguindo a mesma receita de `V2`.
- Docker Compose local (Postgres + PgBouncer em transaction pooling) pra
  testar isso de verdade.
- Teste de isolamento: dois tenants, inserir dado em cada um, confirmar via
  `SET LOCAL app.tenant_id` que um nunca enxerga o dado do outro mesmo numa
  query sem `WHERE tenant_id = ...` explícito.
