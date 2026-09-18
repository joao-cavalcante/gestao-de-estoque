-- Ver conferencia-conversao-dizima-periodica: QTDNEG que volta do Sankhya pode
-- ter mais de 5 casas decimais (ex.: fator 1/12 = 0,083333333...). Gravar em
-- decimal(15,5) truncava esse valor ANTES de devolvê-lo pro Sankhya em
-- ConferenciaSP.salvarItemConferido, quando o certo é ecoar o mesmo valor
-- exato recebido, sem perder precisão no meio do caminho.
alter table app.separacao_itens alter column qtd_neg type numeric(20, 15);
alter table app.separacao_itens alter column qtd_entregue type numeric(20, 15);
alter table app.separacao_itens alter column qtd_conferida_local type numeric(20, 15);
alter table app.separacao_itens alter column fator_conversao type numeric(20, 15);
