-- V61 — quantidade comercial informada na pesagem (usuário, 23/10/2026).
--
-- O pesável continua conferido em KG (separacao_leituras.qtd = peso líquido, é o que vai pro Sankhya). No pop-up de
-- pesagem o conferente informa também quantas unidades do pedido (CX, UN, PC) estão naquela pesagem; fica gravado na
-- própria leitura, ao lado do peso. A etiqueta de peso soma essas quantidades do item, como já soma o peso.
alter table app.separacao_leituras add column qtd_comercial numeric(15, 5);
alter table app.separacao_leituras add column unidade_comercial text;

alter table app.etiquetas_peso add column qtd_comercial numeric(15, 5);
alter table app.etiquetas_peso add column unidade_comercial text;
