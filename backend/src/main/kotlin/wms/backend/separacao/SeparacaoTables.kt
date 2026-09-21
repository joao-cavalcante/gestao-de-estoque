package wms.backend.separacao

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import wms.backend.tarefas.jsonb

/** app.separacao_sessoes — ver db/migrations/V7__separacao.sql pro raciocínio completo. */
object SeparacaoSessoesTable : Table("app.separacao_sessoes") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val nunota = integer("nunota")
    val tarefaId = uuid("tarefa_id").nullable()
    val status = text("status")
    val erro = text("erro").nullable()
    val statusOperacionalSnapshot = text("status_operacional_snapshot")
    val fingerprintItens = text("fingerprint_itens").nullable()
    val buscarCodigoBarraPor = text("buscar_codigo_barra_por")
    val qtdAmaior = text("qtdamaior").nullable()
    val nuconf = integer("nuconf").nullable()
    val obterQtdBalanca = text("obter_qtd_balanca").nullable()
    val produtosForaPed = text("produtos_fora_ped").nullable()
    // Snapshot do módulo por-tenant "conferência segmentada" (ver Modulos.kt / V25) —
    // congelado na abertura da sessão, igual aos campos de CCO acima.
    val conferenciaSegmentada = bool("conferencia_segmentada")
    // CCO.FATAOCONCLUIR (V26) — 'S' = oferecer faturamento após finalizar a conferência.
    val fatAoConcluir = text("fat_ao_concluir").nullable()
    // CCO "Comportamento da interface" (V28) — snapshot na abertura, gateiam painéis da tela.
    // Semântica: só 'N' explícito esconde; NULL/ausente/'S'/outro = mostra.
    val exibirProd = text("exibir_prod").nullable()
    val exibirQtd = text("exibir_qtd").nullable()
    val exibirProdConf = text("exibir_prod_conf").nullable()
    val exibirQtdConf = text("exibir_qtd_conf").nullable()
    val exibirImgProd = text("exibir_img_prod").nullable()
    // Quantidade de volumes (modo simplificado, V30) — contador local, +/- na UI;
    // empurrado pro Sankhya só no `cortar` da finalização.
    val qtdVol = integer("qtd_vol").default(0)
    val criadoEm = timestamp("criado_em")
    val atualizadoEm = timestamp("atualizado_em")
    /** Quem bipou o crachá pra "assumir" esta conferência (V33) — null até a primeira bipagem. */
    val operadorId = uuid("operador_id").nullable()
    /** Qual conta estava logada no navegador quando o crachá foi bipado (V35) — ex.: "Stage1"/"Stage2". */
    val estacaoId = uuid("estacao_id").nullable()
    /** CCO.FORMACAOVOLUMES (V37) — gateia a exigência de volume > 0 pra finalizar. */
    val formacaoVolumes = text("formacao_volumes").nullable()
    /** V44 — sessão de recontagem; `volumeBase` = volumes já numerados nas conferências anteriores da nota. */
    val recontagem = bool("recontagem").default(false)
    val volumeBase = integer("volume_base").default(0)

    override val primaryKey = PrimaryKey(id)
}

object SeparacaoItensTable : Table("app.separacao_itens") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val sessaoId = uuid("sessao_id")
    val sequencia = integer("sequencia")
    val codprod = integer("codprod")
    val controle = text("controle")
    val codvol = text("codvol").nullable()
    // V41: 20,15 (não 15,5) — QTDNEG do Sankhya pode vir com dízima periódica
    // (ex.: fator 1/12 = 0,083333333...) e precisa ser ecoada de volta pra
    // ConferenciaSP.salvarItemConferido sem perder casa decimal no meio do
    // caminho. Ver conferencia-conversao-dizima-periodica na memória.
    val qtdNeg = decimal("qtd_neg", 20, 15)
    val qtdEntregue = decimal("qtd_entregue", 20, 15)
    val qtdConferidaLocal = decimal("qtd_conferida_local", 20, 15)
    val usaConfPeso = bool("usa_conf_peso")
    val foraPedido = bool("fora_pedido")
    // Unidades alternativas (V27) — snapshot na abertura, só p/ display "Pedido: X CX".
    val unidadeComercial = text("unidade_comercial").nullable()
    val unidadePadrao = text("unidade_padrao").nullable()
    val divideMultiplica = text("divide_multiplica").nullable()
    val fatorConversao = decimal("fator_conversao", 20, 15).nullable()
    // Conferência por etapa (V29) — tipo de separação do produto (TGFPRO.AD_TIPOSEPARACAO):
    // 1 Secos | 2 Resfriados | 3 Congelados. Ausência/inválido = 1 (default no banco).
    val tipoSeparacao = short("tipo_separacao")
    val dados = jsonb("dados")
    // V39 — item já liberado numa rodada de corte anterior, auto-conferido em
    // silêncio (ver SeparacaoService.carregarEmBackground): nunca aparece pro
    // operador em Pendentes nem em Conferidos, mas entra normalmente no
    // finalizar() (via leitura já gravada) pra subir a quantidade aceita pro
    // Sankhya.
    val silencioso = bool("silencioso")
    // V43 - já enviado ao Sankhya (salvarItemConferido) — ver SeparacaoService.enviarGruposAoSankhya.
    val enviadoSankhya = bool("enviado_sankhya").default(false)

    override val primaryKey = PrimaryKey(id)
}

/**
 * app.separacao_etapas (V29) — etapas da conferência segmentada. Uma linha por
 * tipo_separacao que tem item na nota; a conferência só finaliza no Sankhya
 * quando todas estão 'C'. Só existe quando o módulo `conferencia_segmentada`
 * está ligado pro tenant.
 */
object SeparacaoEtapasTable : Table("app.separacao_etapas") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val sessaoId = uuid("sessao_id")
    val tipoSeparacao = short("tipo_separacao")
    val status = text("status")             // 'P' pendente | 'C' concluída
    // Volume (modo simplificado) contado nesta etapa (V31). Finalização soma todas.
    val qtdVol = integer("qtd_vol").default(0)
    val concluidaPor = text("concluida_por").nullable()
    val concluidaEm = timestamp("concluida_em").nullable()
    val criadoEm = timestamp("criado_em")

    override val primaryKey = PrimaryKey(id)
}

/** Status de app.separacao_etapas.status. */
object SeparacaoEtapaStatus {
    const val PENDENTE = "P"
    const val CONCLUIDA = "C"
}

object SeparacaoCodigosBarraTable : Table("app.separacao_codigos_barra") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val sessaoId = uuid("sessao_id")
    val codigoBarra = text("codigo_barra")
    val codprod = integer("codprod")
    val codvol = text("codvol").nullable()
    val controle = text("controle")
    val origem = text("origem")
    val quantidade = decimal("quantidade", 15, 5).nullable()
    val divideMultiplica = text("divide_multiplica").nullable()

    override val primaryKey = PrimaryKey(id)
}

object SeparacaoLeiturasTable : Table("app.separacao_leituras") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val sessaoId = uuid("sessao_id")
    val codprod = integer("codprod")
    val controle = text("controle")
    val codvol = text("codvol").nullable()
    val codigoBarra = text("codigo_barra").nullable()
    val qtd = decimal("qtd", 15, 5)
    val peso = decimal("peso", 15, 5).nullable()
    val criadoEm = timestamp("criado_em")

    override val primaryKey = PrimaryKey(id)
}

/**
 * app.separacao_uma — UMA (Unidade de Movimentação e Armazenagem) não é
 * catálogo persistente nem no Sankhya nem no projeto base: é lida ao vivo
 * por sessão (join UnidadeMovArmazenagemProduto->UnidadeMovimentacaoArmazenagem)
 * e só cacheada durante a conferência, mesmo espírito de
 * SeparacaoCodigosBarraTable — nunca fonte de verdade.
 */
object SeparacaoUmaTable : Table("app.separacao_uma") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val sessaoId = uuid("sessao_id")
    val codprod = integer("codprod")
    val coduma = integer("coduma")
    val descricao = text("descricao").nullable()
    val peso = decimal("peso", 15, 5).nullable()
    val codvol = text("codvol").nullable()
    val codbarra = text("codbarra").nullable()
    val padrao = bool("padrao")

    override val primaryKey = PrimaryKey(id)
}

/**
 * app.separacao_corte_liberacoes (V36) — decisão (liberado/negado) mais
 * recente de cada item numa rodada de liberação de corte. Ver migração pro
 * raciocínio completo (TGFITE não reflete essa decisão em campo nenhum).
 */
object SeparacaoCorteLiberacoesTable : Table("app.separacao_corte_liberacoes") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val nunota = integer("nunota")
    val codprod = integer("codprod")
    val liberado = bool("liberado")
    val nuconf = integer("nuconf")
    val decididoEm = timestamp("decidido_em")
    // V38 — controle do item liberado (pra achar a linha certa na recontagem,
    // produto com controle de lote pode ter mais de uma) e a quantidade que
    // foi de fato aceita (a que apareceu como "Qtd. total conf." na
    // liberação) — usados pra auto-conferir esse item em silêncio quando ele
    // reaparece na recontagem, sem o operador precisar bipar de novo o que
    // já foi aceito.
    val controle = text("controle").nullable()
    val qtdLiberada = decimal("qtd_liberada", 15, 5).nullable()

    override val primaryKey = PrimaryKey(id)
}

/** Status possíveis de app.separacao_sessoes.status — texto simples, sem enum de banco (mesmo estilo de StatusOperacional). */
object SeparacaoStatus {
    const val CARREGANDO = "carregando"
    const val PRONTA = "pronta"
    const val ERRO = "erro"
    const val INVALIDADA = "invalidada"
    const val CONCLUIDA = "concluida"
    const val CANCELADA = "cancelada"
}

/** app.etiquetas_peso (V42) — etiquetas de produto pesável, número único gerado pelo banco. */
object EtiquetasPesoTable : Table("app.etiquetas_peso") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val numero = long("numero").autoIncrement()
    val sessaoId = uuid("sessao_id")
    val nunota = integer("nunota")
    val nuconf = integer("nuconf").nullable()
    val codprod = integer("codprod")
    val controle = text("controle")
    val produto = text("produto")
    val peso = decimal("peso", 20, 3)
    val cliente = text("cliente")
    val ativa = bool("ativa")
    /** V44 — etiqueta emitida numa recontagem, no lugar da etiqueta do item na conferência original. */
    val correcao = bool("correcao").default(false)
    val substituiNumero = long("substitui_numero").nullable()
    val impressoes = integer("impressoes")
    val criadoEm = timestamp("criado_em")
    val ultimaImpressaoEm = timestamp("ultima_impressao_em")

    override val primaryKey = PrimaryKey(id)
}

/** app.separacao_operador_historico (V45) — cada crachá bipado numa sessão, com o operador anterior. */
object SeparacaoOperadorHistoricoTable : Table("app.separacao_operador_historico") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val sessaoId = uuid("sessao_id")
    val operadorId = uuid("operador_id")
    val operadorAnteriorId = uuid("operador_anterior_id").nullable()
    val estacaoId = uuid("estacao_id").nullable()
    val identificadoEm = timestamp("identificado_em")

    override val primaryKey = PrimaryKey(id)
}
