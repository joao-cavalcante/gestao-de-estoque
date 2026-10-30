-- ============================================================================
-- V68 — login Master protegido (Entrega 1.2).
--
-- tenancy.master_login_tentativas: cada tentativa de login Master (sucesso ou não), por e-mail informado e IP.
-- O limite de tentativas conta daqui — no banco, não na memória do processo, então vale com mais de uma réplica
-- e sobrevive a restart. Bloqueio é SEMPRE temporário (janela deslizante); nada aqui desativa conta.
-- Nunca guarda senha nem código: só e-mail digitado (normalizado), IP e resultado.
-- `bloqueada` = tentativa recusada pelo próprio limite (não conta como falha, senão o bloqueio nunca venceria).
-- DELETE pro wms_app: limpeza das tentativas antigas e o `desbloquear` da ferramenta CriarMasterKt.
-- A trilha administrativa continua sendo tenancy.auditoria_admin (sem UPDATE/DELETE).
--
-- tenancy.master_users: segredo TOTP do MFA (cifrado com a mesma cifra das credenciais de ERP) e o último passo
-- de 30 s aceito (um código não vale duas vezes).
-- ============================================================================

create table tenancy.master_login_tentativas (
  id           bigint generated always as identity primary key,
  ocorrido_em  timestamptz not null default now(),
  email        text not null,
  ip           text not null,
  sucesso      boolean not null,
  bloqueada    boolean not null default false
);

create index idx_master_login_tent_email on tenancy.master_login_tentativas (email, ocorrido_em desc);
create index idx_master_login_tent_ip on tenancy.master_login_tentativas (ip, ocorrido_em desc);

grant select, insert, delete on tenancy.master_login_tentativas to wms_app;

alter table tenancy.master_users
  add column mfa_segredo      text,
  add column mfa_ultimo_passo bigint;

grant update (mfa_segredo, mfa_ultimo_passo) on tenancy.master_users to wms_app;
