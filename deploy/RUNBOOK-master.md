# Runbook — Painel Master (Entregas 1, 1.1 e 1.2)

Publicação da administração da plataforma (Painel Master) com segurança reforçada. **Este documento não autoriza o
deploy**: cada passo marcado 🔒 precisa de autorização explícita do responsável.

Produção hoje (`origin/master` = `054ff79`) está **antes da Entrega 1**. O deploy leva junto:

| Entrega | Migração | O que muda no banco |
|---|---|---|
| 1 | V62–V66 | `erp_connections.parametros` (jsonb) + parâmetros da Negri congelados (liberação herdada evento 3, liberador por variável de ambiente, tolerância de pesável, fuso, criação de usuário Sankhya) |
| 1.1 | V67 | `tenancy.auditoria_admin` (só INSERT/SELECT pro `wms_app`), `INSERT` em `master_users` |
| 1.2 | V68 | `tenancy.master_login_tentativas`, `master_users.mfa_segredo` / `mfa_ultimo_passo` |

Todas são **aditivas** (tabelas/colunas novas; a única atualização preenche a coluna nova da Negri). O código
anterior ignora o que é novo — rollback é só de código, sem reverter banco.

O que muda para quem usa:

- A tela antiga `/tenants` (aberta a qualquer ADMINISTRADOR de cliente) **deixa de existir**. Administração da
  plataforma passa a ser só `/master`, com conta Master própria (e-mail + senha + código do aplicativo autenticador).
- Tenant suspenso perde o acesso **na hora** (inclusive sessões já abertas) e para de sincronizar.
- Operação da Negri (conferência, pesagem, corte, liberação, carregamento, faturamento, impressão): sem mudança de
  comportamento. Os parâmetros que antes estavam no código ficam explícitos na configuração dela (V62–V66).

---

## 0. Variáveis novas (`.env` ao lado do `docker-compose.prod.yml`)

| Variável | Obrigatória | Valor |
|---|---|---|
| `WMS_MASTER_JWT_SECRET` | **sim** (sem ela o Painel Master responde 503; o resto funciona) | `openssl rand -hex 32` — **diferente** do `WMS_JWT_SECRET` |
| `WMS_MASTER_MFA_OBRIGATORIO` | não (padrão `true`) | manter `true` |
| `WMS_MASTER_REDES_PERMITIDAS` | recomendada | CIDR da VPN/rede administrativa, ex. `10.8.0.0/24`. Vazio = qualquer rede |
| `WMS_PROXIES_CONFIAVEIS` | **sim** (senão o limite por IP conta todo mundo como o IP do nginx) | sub-rede da rede docker do compose (ver 1.4) |

Nunca colar esses valores em chat, ticket ou commit. O `.env` já está no `.gitignore`.

---

## 1. Antes do deploy

### 1.1 Ensaio no staging (obrigatório)
Fluxo do projeto: `develop → staging → master`. Fazer **tudo** deste runbook primeiro em `~/projetos/wms-staging`
(`docker-compose.staging.yml`, containers `wms-*-staging`, API 3004, web 9006), com as mesmas variáveis (valores
próprios do staging). Só seguir para produção com o checklist do item 3 todo verde no staging.

### 1.2 Janela
- Fora do horário de expedição. Antes de subir, conferir no log que não há operação em andamento:
  `docker logs --since 10m wms-backend-prod 2>&1 | grep -E 'salvarItemConferido|cortar|finalizarConferencia|liberarNegar|faturar|confirmarNota'`
  (vazio = pode seguir; o restart derruba finalização em andamento).

### 1.3 Backup 🔒
```bash
cd ~/projetos/wms
mkdir -p ~/backups
docker exec wms-db-prod pg_dump -U postgres -Fc wms > ~/backups/wms-antes-master-$(date +%Y%m%d-%H%M).dump
ls -lh ~/backups/ | tail -1          # tamanho coerente com os backups anteriores (não zero)
cp .env ~/backups/env-antes-master-$(date +%Y%m%d-%H%M)
git rev-parse --short HEAD           # anotar: é o ponto de rollback (hoje 054ff79)
```

### 1.4 Configuração 🔒
```bash
# sub-rede da rede do compose (o nginx do wms-frontend fala com o backend por ela)
docker network inspect wms_default --format '{{(index .IPAM.Config 0).Subnet}}'
# editar .env: WMS_MASTER_JWT_SECRET (novo, 64 hex), WMS_MASTER_MFA_OBRIGATORIO=true,
#              WMS_MASTER_REDES_PERMITIDAS=<faixa da VPN>, WMS_PROXIES_CONFIAVEIS=<sub-rede acima>
grep -c '^WMS_MASTER_JWT_SECRET=..*' .env          # 1
[ "$(grep ^WMS_MASTER_JWT_SECRET= .env | cut -d= -f2)" != "$(grep ^WMS_JWT_SECRET= .env | cut -d= -f2)" ] && echo diferentes
```

### 1.5 Migrações pendentes
```bash
docker exec wms-db-prod psql -U postgres -d wms -tAc \
  "select version from public.schema_migrations where version ~ '^V6[0-9]$' order by version"
# esperado hoje: até V61. Depois do deploy: V62..V68.
```

### 1.6 Configuração atual da Negri (para comparar depois)
```bash
docker exec wms-db-prod psql -U postgres -d wms -tAc \
  "select slug, status from tenancy.tenants order by slug" > ~/backups/tenants-antes.txt
cat ~/backups/tenants-antes.txt
```

### 1.7 Pré-requisitos do bootstrap
- Pessoa que será o primeiro Master com celular e aplicativo autenticador (Google/Microsoft Authenticator, 1Password).
- Acesso SSH ao servidor e terminal interativo (`docker exec -it`).

---

## 2. Durante o deploy 🔒

Ordem (o compose já garante 2 → 3 → 4):

1. `git pull` (branch `master` já com `develop` promovido via `staging`).
2. `wms-migrate` aplica V62–V68 e sai (o backend só sobe se ele terminar com sucesso).
3. `wms-backend` sobe.
4. `wms-frontend` sobe.

```bash
cd ~/projetos/wms && git pull
export APP_BUILD=$(git rev-list --count HEAD) APP_COMMIT=$(git rev-parse --short HEAD)
docker compose -f docker-compose.prod.yml up -d --build
docker logs wms-migrate-prod | tail -12          # "V62 — aplicando" ... "V68 — aplicando", sem ERROR
docker compose -f docker-compose.prod.yml ps     # migrate Exited (0); backend e frontend Up
curl -s localhost:3003/health                    # {"status":"ok"}
curl -s -o /dev/null -w '%{http_code}\n' localhost:9005   # 200
docker logs wms-backend-prod 2>&1 | grep -E 'Painel Master DESLIGADO'   # VAZIO (senão o .env não tem a chave)
```

Se o `wms-migrate` falhar: **parar aqui** e ir para o rollback (item 5) — o backend antigo continua de pé.

### 2.1 Login operacional (antes do bootstrap — a operação não depende do Master)
- Abrir `http://<servidor>:9005`, entrar com um usuário da Negri, abrir a Fila de Conferência.

### 2.2 Bootstrap do primeiro Master
Ver o item 6 (procedimento completo). Resumo:
```bash
docker exec -it wms-backend-prod sh -c 'java -cp "/app/lib/*" wms.backend.tools.CriarMasterKt situacao'
docker exec -it wms-backend-prod sh -c 'java -cp "/app/lib/*" wms.backend.tools.CriarMasterKt criar <email> "<Nome>"'
```

### 2.3 Rotas administrativas
```bash
# rota antiga não existe mais
curl -s -o /dev/null -w '%{http_code}\n' localhost:3003/api/tenants                  # 404
# plataforma sem token
curl -s -o /dev/null -w '%{http_code}\n' localhost:3003/api/master/tenants           # 401 (ou 404 se fora da rede permitida)
# não existe cadastro de Master por API
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:3003/api/master/usuarios  # 401/404, nunca 2xx
```

---

## 3. Depois do deploy (checklist de aceite)

| # | Verificação | Como | Esperado |
|---|---|---|---|
| 1 | Login Master | `/master/login` com e-mail, senha e código | lista de clientes |
| 2 | Código repetido | sair e entrar de novo com o MESMO código | recusado; com o próximo código, entra |
| 3 | Cadastro da Negri | abrir Negri no painel | dados gerais, integração (credenciais "gravadas (cifradas)", nunca exibidas) |
| 4 | Parâmetros existentes | aba Parâmetros operacionais | liberação herdada evento 3 sem comparar valor; liberador "instalação legada"; tolerância acima sem limite / abaixo 5%; fuso America/Sao_Paulo; criação de usuário Sankhya com modelo 194, grupo 32, negri.local |
| 5 | Diagnóstico | aba Diagnóstico → Executar | só leituras; erros = 0 |
| 6 | Auditoria | aba Histórico / `tenancy.auditoria_admin` | login, diagnóstico registrados com quem/quando |
| 7 | Admin da Negri | login operacional de um ADMINISTRADOR da Negri | entra; Usuários, Tipos de Operação abrem |
| 8 | Admin da Negri NÃO é Master | com a sessão do item 7, `GET /api/master/tenants` | 401 |
| 9 | Nenhum tenant suspenso | `select slug,status from tenancy.tenants` | igual a `~/backups/tenants-antes.txt` |
| 10 | Operação | conferir 1 pedido real na Fila (rotina normal da expedição) | sem diferença de comportamento |
| 11 | Limite de tentativas | (no staging) 5 senhas erradas | 6ª tentativa: "aguarde 15 minutos" |

```bash
docker exec wms-db-prod psql -U postgres -d wms -tAc "select slug,status from tenancy.tenants order by slug" | diff - ~/backups/tenants-antes.txt && echo "situações iguais"
docker exec wms-db-prod psql -U postgres -d wms -tAc "select ocorrido_em, ator_email, acao, resultado from tenancy.auditoria_admin order by id desc limit 10"
```

---

## 4. Proteção de acesso do Painel Master

Camadas, todas ativas na configuração recomendada:

1. **Chave própria** (`WMS_MASTER_JWT_SECRET`): token Master com emissor `wms-plataforma`, audiência `wms-master`,
   escopo `plataforma`, 4 h. Token de cliente nunca vale no painel, e vice-versa.
2. **Senha + TOTP** (MFA obrigatório). Código vale uma vez.
3. **Limite de tentativas** (no banco, vale com várias réplicas): 5 falhas por conta+IP, 20 por conta, 30 por IP,
   em 15 minutos. Sempre temporário — nada desativa conta sozinho. Resposta idêntica para conta inexistente.
4. **Rede** (`WMS_MASTER_REDES_PERMITIDAS`): fora da faixa, `/api/master` responde 404.
5. **Conta ativa conferida a cada chamada**: desativar um Master corta a sessão dele na hora.

Exposição pública: com 1–3 o painel tem proteção de nível adequado; **recomenda-se manter 4 (VPN/rede
administrativa)** enquanto a porta 9005 for HTTP sem TLS — sem TLS, senha e código trafegam em claro na rede.

Se em algum momento for preciso desligar o MFA (`WMS_MASTER_MFA_OBRIGATORIO=false`), isso só pode ser feito com a
restrição de rede ligada, e deve voltar a `true` assim que o MFA for recadastrado.

---

## 5. Rollback 🔒

Gatilhos: `wms-migrate` falhou; backend não sobe; login operacional da Negri falha; qualquer diferença na operação.

**Código** (banco fica — as migrações são aditivas):
```bash
cd ~/projetos/wms
git checkout 054ff79                      # ponto anotado em 1.3
export APP_BUILD=$(git rev-list --count HEAD) APP_COMMIT=$(git rev-parse --short HEAD)
docker compose -f docker-compose.prod.yml up -d --build
curl -s localhost:3003/health; curl -s -o /dev/null -w '%{http_code}\n' localhost:9005
# depois que estabilizar: voltar o clone para a branch
git checkout master
```
Efeito: volta a tela antiga `/tenants` (com a falha de permissão conhecida da 1.1) e os parâmetros da Negri voltam
a vir do código (mesmos valores). Tabelas/colunas novas ficam sem uso.

**Banco** — só se o próprio `wms-migrate` corrompeu algo (não esperado; as migrações rodam uma a uma):
```bash
docker compose -f docker-compose.prod.yml stop wms-backend
docker exec -i wms-db-prod pg_restore -U postgres -d wms --clean --if-exists < ~/backups/wms-antes-master-<data>.dump
docker compose -f docker-compose.prod.yml start wms-backend
```
Restaurar o banco descarta TUDO o que a operação gravou depois do backup — último recurso, com autorização.

**Só o Painel Master com problema** (operação ok): remover `WMS_MASTER_JWT_SECRET` do `.env` e
`docker compose -f docker-compose.prod.yml up -d wms-backend` → painel 503, operação intacta.

---

## 6. Procedimento do primeiro Master (bootstrap)

Não existe rota de cadastro Master, nem senha padrão no repositório. A ferramenta roda **dentro** do container do
backend, com a conexão dele com o banco (`wms_app`), e registra cada ação na auditoria (ator `sistema`, via `cli`).

```bash
M='docker exec -it wms-backend-prod sh -c'      # -it: senha sem eco e confirmação do código
CP='java -cp "/app/lib/*" wms.backend.tools.CriarMasterKt'

# 1) existe Master ativo?
$M "$CP situacao"            # "Nenhum Master ativo — rode: criar <email> <nome>"

# 2) criar (senha digitada 2x, sem eco; nunca em argumento)
$M "$CP criar plataforma@empresa.com.br 'Nome Sobrenome'"
#    → mostra a CHAVE e o endereço otpauth:// UMA vez: cadastrar no aplicativo autenticador agora
#    → pede um código do aplicativo e confere ("Código confere — aplicativo cadastrado.")

# 3) conferir
$M "$CP situacao"            # 1 ativo, "MFA cadastrado"

# 4) primeiro login: /master/login com e-mail, senha e código → lista de clientes
#    auditoria: select acao, resultado from tenancy.auditoria_admin order by id desc limit 3;
```

Proteções da ferramenta: recusa segundo `criar` se já houver Master ativo (Master adicional só com `--adicional`),
recusa e-mail repetido, senha < 12 caracteres ou igual ao e-mail, relê a conta gravada e confere senha e segredo
antes de declarar sucesso.

### Recuperação administrativa (sempre pelo servidor; nada disso existe por API)

| Situação | Comando |
|---|---|
| Cadastro inicial falhou no meio | `situacao`; se a conta aparecer: `redefinir-senha` / `redefinir-mfa`. Se não aparecer: `criar` de novo |
| Esqueceu a senha | `redefinir-senha <email>` |
| Perdeu o celular / aplicativo | `redefinir-mfa <email>` (o código antigo para de valer) |
| Bloqueado por tentativas (antes dos 15 min) | `desbloquear <email>` |
| Master saiu da empresa | `criar <novo> "<Nome>" --adicional` e depois `desativar <email>` (o último Master ativo não pode ser desativado) |
| Reativar | `reativar <email>` |

Ninguém fica trancado: o bloqueio por tentativas é temporário e o acesso ao servidor (SSH + docker) sempre permite
recuperar uma conta.

---

## 7. Riscos conhecidos (aceitos ou pendentes)

- **Sem TLS na 9005**: credenciais e token trafegam em claro. Mitigação: rede administrativa/VPN. Pendente: HTTPS.
- **Porta 3003 publicada**: a API também responde direto. O `X-Real-IP` só é aceito vindo de `WMS_PROXIES_CONFIAVEIS`;
  chamadas de fora chegam com o IP real e não conseguem forjar origem. Quem tem acesso local ao servidor consegue —
  avaliar publicar a 3003 só em `127.0.0.1` se nenhum agente a usa diretamente.
- **Mais de uma réplica do backend** (não é o caso hoje): a suspensão vale em até 15 s nas outras réplicas (cache
  da situação do tenant); o limite de tentativas e o "código vale uma vez" já são no banco.
- **Token Master no `localStorage`** (como o do tenant): validade de 4 h, conta conferida a cada chamada.
- `WMS_JWT_SECRET` dos clientes ainda tem valor de desenvolvimento como reserva no código se a variável faltar
  (comportamento herdado, mantido para não mexer na autenticação operacional); produção define a variável.
