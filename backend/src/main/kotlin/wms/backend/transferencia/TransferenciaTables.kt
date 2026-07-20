package wms.backend.transferencia

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** app.modelos_nota — ver db/migrations/V12__transferencias.sql. */
object ModelosNotaTable : Table("app.modelos_nota") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val tipo = text("tipo")
    val codtop = integer("codtop")
    val codemp = integer("codemp")
    val codnat = integer("codnat")
    val ativo = bool("ativo")
    val criadoEm = timestamp("criado_em")
    val atualizadoEm = timestamp("atualizado_em")

    override val primaryKey = PrimaryKey(id)
}

/** app.locais_estoque — STUB de cadastro (sem integração Sankhya real ainda, ver V12). */
object LocaisEstoqueTable : Table("app.locais_estoque") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val codigo = text("codigo")
    val ativo = bool("ativo")
    val criadoEm = timestamp("criado_em")

    override val primaryKey = PrimaryKey(id)
}

/** app.produtos_estoque — STUB de cadastro (mesma ressalva de LocaisEstoqueTable). */
object ProdutosEstoqueTable : Table("app.produtos_estoque") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val codigo = text("codigo")
    val nome = text("nome")
    val ctrl = text("ctrl")
    val modo = text("modo") // unit | labelqty | bulk
    val qtdEtiqueta = decimal("qtd_etiqueta", 15, 5).nullable()
    val unidade = text("unidade")
    val criadoEm = timestamp("criado_em")

    override val primaryKey = PrimaryKey(id)
}

object SaldosProdutoLocalTable : Table("app.saldos_produto_local") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val codigoProduto = text("codigo_produto")
    val codigoLocal = text("codigo_local")
    val saldo = decimal("saldo", 15, 5)

    override val primaryKey = PrimaryKey(id)
}

object TransferenciasTable : Table("app.transferencias") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val origem = text("origem")
    val destino = text("destino")
    val canalOrigem = text("canal_origem") // coletor | desktop
    val status = text("status") // aberta | confirmada | cancelada
    val operadorUserId = uuid("operador_user_id")
    val operadorNome = text("operador_nome")
    val pendenteWriteBack = bool("pendente_write_back")
    val criadoEm = timestamp("criado_em")
    val atualizadoEm = timestamp("atualizado_em")
    val confirmadoEm = timestamp("confirmado_em").nullable()

    override val primaryKey = PrimaryKey(id)
}

object TransferenciaItensTable : Table("app.transferencia_itens") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val transferenciaId = uuid("transferencia_id")
    val codigoProduto = text("codigo_produto")
    val nomeProduto = text("nome_produto")
    val controle = text("controle")
    val quantidade = decimal("quantidade", 15, 5)
    val unidade = text("unidade")
    val criadoEm = timestamp("criado_em")

    override val primaryKey = PrimaryKey(id)
}

object TransferenciaStatus {
    const val ABERTA = "aberta"
    const val CONFIRMADA = "confirmada"
    const val CANCELADA = "cancelada"
    /** Rascunho 'aberta' sem itens e sem atividade prolongada — ver TransferenciaAbandonoWorker. */
    const val ABANDONADA = "abandonada"
}

object CanalOrigem {
    const val COLETOR = "coletor"
    const val DESKTOP = "desktop"
}
