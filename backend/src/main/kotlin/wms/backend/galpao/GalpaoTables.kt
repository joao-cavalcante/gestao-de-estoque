package wms.backend.galpao

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import wms.backend.tarefas.jsonb

/** Ver V71__galpao_planta.sql. */
object GalpaoPlantasTable : Table("app.galpao_plantas") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val nome = text("nome")
    val codemp = integer("codemp").nullable()
    val codlocal = integer("codlocal").nullable()
    val largura = decimal("largura", 10, 2)
    val profundidade = decimal("profundidade", 10, 2)
    val versaoPublicadaId = uuid("versao_publicada_id").nullable()
    val ativo = bool("ativo")
    val criadoEm = timestamp("criado_em")
    val atualizadoEm = timestamp("atualizado_em")

    override val primaryKey = PrimaryKey(id)
}

object GalpaoVersoesTable : Table("app.galpao_geometria_versoes") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val plantaId = uuid("planta_id")
    val numero = integer("numero")
    val status = text("status")
    val geometria = jsonb("geometria")
    val hash = text("hash")
    val observacao = text("observacao").nullable()
    val criadoPorNome = text("criado_por_nome")
    val criadoEm = timestamp("criado_em")
    val publicadoPorNome = text("publicado_por_nome").nullable()
    val publicadoEm = timestamp("publicado_em").nullable()

    override val primaryKey = PrimaryKey(id)
}

object GalpaoEnderecosTable : Table("app.galpao_enderecos") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val plantaId = uuid("planta_id")
    val codigo = text("codigo")
    val setor = text("setor")
    val estrutura = text("estrutura")
    val modulo = integer("modulo")
    val nivel = integer("nivel")
    val tipo = text("tipo")
    val x = decimal("x", 10, 3)
    val y = decimal("y", 10, 3)
    val z = decimal("z", 10, 3)
    val rotacao = decimal("rotacao", 7, 2)
    val largura = decimal("largura", 10, 3)
    val comprimento = decimal("comprimento", 10, 3)
    val altura = decimal("altura", 10, 3)
    val capQtd = decimal("cap_qtd", 15, 5).nullable()
    val capPeso = decimal("cap_peso", 15, 5).nullable()
    val capVolume = decimal("cap_volume", 15, 5).nullable()
    val bloqueado = bool("bloqueado")
    val ativo = bool("ativo")
    val atualizadoEm = timestamp("atualizado_em")

    override val primaryKey = PrimaryKey(id)
}

object GalpaoSaldosEnderecoTable : Table("app.galpao_saldos_endereco") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val enderecoId = uuid("endereco_id")
    val codprod = integer("codprod")
    val controle = text("controle")
    val quantidade = decimal("quantidade", 15, 5)
    val versao = integer("versao")
    val atualizadoEm = timestamp("atualizado_em")

    override val primaryKey = PrimaryKey(id)
}

object StatusVersao {
    const val RASCUNHO = "RASCUNHO"
    const val PUBLICADA = "PUBLICADA"
    const val ARQUIVADA = "ARQUIVADA"
}

/** Ver V72__galpao_movimentacao.sql — só INSERT (trigger recusa UPDATE/DELETE). */
object GalpaoMovimentosTable : Table("app.galpao_movimentos") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val plantaId = uuid("planta_id")
    val comandoId = uuid("comando_id")
    val tipo = text("tipo")
    val enderecoOrigemId = uuid("endereco_origem_id").nullable()
    val enderecoDestinoId = uuid("endereco_destino_id").nullable()
    val codprod = integer("codprod").nullable()
    val controle = text("controle")
    val quantidade = decimal("quantidade", 15, 5)
    val motivo = text("motivo").nullable()
    val contagemId = uuid("contagem_id").nullable()
    val usuarioId = uuid("usuario_id")
    val usuarioNome = text("usuario_nome")
    val criadoEm = timestamp("criado_em")

    override val primaryKey = PrimaryKey(id)
}

object GalpaoContagensTable : Table("app.galpao_contagens") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val plantaId = uuid("planta_id")
    val enderecoId = uuid("endereco_id")
    val comandoId = uuid("comando_id")
    val codprod = integer("codprod")
    val controle = text("controle")
    val quantidadeContada = decimal("quantidade_contada", 15, 5)
    val quantidadeSistema = decimal("quantidade_sistema", 15, 5)
    val versaoSaldo = integer("versao_saldo")
    val motivo = text("motivo").nullable()
    val status = text("status")
    val contadoPorNome = text("contado_por_nome")
    val contadoEm = timestamp("contado_em")
    val decididoPorNome = text("decidido_por_nome").nullable()
    val decididoEm = timestamp("decidido_em").nullable()
    val observacaoDecisao = text("observacao_decisao").nullable()

    override val primaryKey = PrimaryKey(id)
}

object TipoMovimento {
    const val ENDERECAR = "ENDERECAR"
    const val TRANSFERIR = "TRANSFERIR"
    const val RETIRAR = "RETIRAR"
    const val AJUSTE_CONTAGEM = "AJUSTE_CONTAGEM"
    const val BLOQUEAR = "BLOQUEAR"
    const val DESBLOQUEAR = "DESBLOQUEAR"
}

object StatusContagem {
    const val PENDENTE = "PENDENTE"
    const val APROVADA = "APROVADA"
    const val DESCARTADA = "DESCARTADA"
}
