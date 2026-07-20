-- ============================================================================
-- V13 — status 'abandonada' pra transferências (rascunho aberto sem
-- atividade prolongada, ver TransferenciaAbandonoWorker). Rascunhos vazios
-- de origem/destino corrigidos pelo operador (ver fluxo de edição no
-- desktop) viram exatamente esse tipo de registro — não são excluídos,
-- só marcados com peso visual reduzido na listagem, pra fins de auditoria.
-- ============================================================================

alter table app.transferencias
  drop constraint transferencias_status_check;

alter table app.transferencias
  add constraint transferencias_status_check
  check (status in ('aberta', 'confirmada', 'cancelada', 'abandonada'));
