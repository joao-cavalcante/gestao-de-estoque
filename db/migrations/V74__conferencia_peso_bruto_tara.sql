-- ============================================================================
-- V74 — peso BRUTO e TARA de cada pesagem (usuário, 11/11/2026: "continuar" a
-- lacuna levantada na varredura da Fila de Conferência nativa).
--
-- Até aqui separacao_leituras.peso guardava só o LÍQUIDO; o bruto da balança e
-- a tara (qtd de embalagens × peso da UMA + tara variável) eram calculados na
-- tela e descartados. A tela nativa manda pesoBruto/pesoLiquido no
-- ConferenciaSP.salvarItemConferido e o Sankhya grava a PesoConferencia
-- (TGFPCON) por item conferido — a Torre passa a fazer o mesmo, somado por
-- produto/controle (ver SeparacaoService.enviarGruposAoSankhya).
--
-- Colunas opcionais: leitura antiga (ou item não pesável) fica nula — no envio
-- o bruto ausente vale o próprio líquido.
-- ============================================================================

alter table app.separacao_leituras
  add column if not exists peso_bruto numeric(15,5),
  add column if not exists tara numeric(15,5),
  add column if not exists coduma integer,
  add column if not exists qtd_uma numeric(15,5);
