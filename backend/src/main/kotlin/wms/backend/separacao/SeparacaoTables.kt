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
    val dados = jsonb("dados")

    override val primaryKey = PrimaryKey(id)
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
    val qtd = decimal("qtd", 15, 5)
    val criadoEm = timestamp("criado_em")

    override val primaryKey = PrimaryKey(id)
}

/** Status possíveis de app.separacao_sessoes.status — texto simples, sem enum de banco (mesmo estilo de StatusOperacional). */
object SeparacaoStatus {
    const val CARREGANDO = "carregando"
    const val PRONTA = "pronta"
    const val ERRO = "erro"
    const val INVALIDADA = "invalidada"
    const val CONCLUIDA = "concluida"
}
