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
    val qtdVol = integer("qtd_vol")
    val criadoEm = timestamp("criado_em")
    val atualizadoEm = timestamp("atualizado_em")

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
    val qtdNeg = decimal("qtd_neg", 15, 5)
    val qtdEntregue = decimal("qtd_entregue", 15, 5)
    val qtdConferidaLocal = decimal("qtd_conferida_local", 15, 5)
    val usaConfPeso = bool("usa_conf_peso")
    val foraPedido = bool("fora_pedido")
    // Unidades alternativas (V27) — snapshot na abertura, só p/ display "Pedido: X CX".
    val unidadeComercial = text("unidade_comercial").nullable()
    val unidadePadrao = text("unidade_padrao").nullable()
    val divideMultiplica = text("divide_multiplica").nullable()
    val fatorConversao = decimal("fator_conversao", 15, 5).nullable()
    // Conferência por etapa (V29) — tipo de separação do produto (TGFPRO.AD_TIPOSEPARACAO):
    // 1 Secos | 2 Resfriados | 3 Congelados. Ausência/inválido = 1 (default no banco).
    val tipoSeparacao = short("tipo_separacao")
    val dados = jsonb("dados")

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

/** Status possíveis de app.separacao_sessoes.status — texto simples, sem enum de banco (mesmo estilo de StatusOperacional). */
object SeparacaoStatus {
    const val CARREGANDO = "carregando"
    const val PRONTA = "pronta"
    const val ERRO = "erro"
    const val INVALIDADA = "invalidada"
    const val CONCLUIDA = "concluida"
    const val CANCELADA = "cancelada"
}
