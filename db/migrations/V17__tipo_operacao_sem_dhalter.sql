-- ============================================================================
-- V17 — Remove dhalter_sankhya de app.tipos_operacao.
--
-- A entidade "TipoOperacao" via CRUDServiceProvider (única forma de consultá-la
-- direto, como o V16 assumia) confirmou-se INCOMPLETA: uma varredura paginada
-- inteira nela nunca lista CODTIPOPER 1011 (CUBAGEM DE PEDIDO), apesar de ser
-- um TOP real, ativo, e corretamente vinculado a um NUCCO (=1) quando resolvido
-- via CabecalhoNota->TipoOperacao.NUCCO — o mesmo caminho que a Fila de
-- Tarefas já usa. Ou seja: a fonte do V16 (consulta própria à TipoOperacao)
-- não é confiável pra decidir "quais TOP existem/importam" — troca-se para
-- derivar o espelho local a partir do que TarefaSyncService já sincroniza em
-- app.tarefas (TipoOperacao.NUCCO agora faz parte do FIELDS de lá). Sem
-- consulta própria ao Sankhya, não há mais DHALTER pra guardar.
-- ============================================================================

alter table app.tipos_operacao drop column dhalter_sankhya;
