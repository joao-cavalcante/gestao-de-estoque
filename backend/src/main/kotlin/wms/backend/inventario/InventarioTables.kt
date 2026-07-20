package wms.backend.inventario

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import wms.backend.tarefas.jsonb

object InventariosTable : Table("app.inventarios") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val descricao = text("descricao")
    val escopoTipo = text("escopo_tipo") // local | grupo_produto | produtos_especificos
    val escopoValores = jsonb("escopo_valores") // lista de códigos (local/produto), como texto JSON cru
    val status = text("status") // aberto | em_contagem | finalizado | ajustado | cancelado
    val abertoPorUserId = uuid("aberto_por_user_id")
    val abertoPorNome = text("aberto_por_nome")
    val abertoEm = timestamp("aberto_em")
    val fechadoEm = timestamp("fechado_em").nullable()
    val ajustadoPorUserId = uuid("ajustado_por_user_id").nullable()
    val ajustadoPorNome = text("ajustado_por_nome").nullable()
    val ajustadoEm = timestamp("ajustado_em").nullable()
    val pendenteWriteBack = bool("pendente_write_back")
    val observacoes = text("observacoes").nullable()

    override val primaryKey = PrimaryKey(id)
}

object InventarioItensTable : Table("app.inventario_itens") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val inventarioId = uuid("inventario_id")
    val produtoCodigo = text("produto_codigo")
    val controle = text("controle")
    val local = text("local")
    val quantidadeSistema = decimal("quantidade_sistema", 15, 5)
    val quantidadeContada = decimal("quantidade_contada", 15, 5)
    val statusItem = text("status_item") // pendente | contado | nao_previsto
    val operadorContagemUserId = uuid("operador_contagem_user_id").nullable()
    val operadorContagemNome = text("operador_contagem_nome").nullable()
    val contadoEm = timestamp("contado_em").nullable()
    val canalOrigem = text("canal_origem").nullable() // coletor | desktop

    override val primaryKey = PrimaryKey(id)
}

object InventarioStatus {
    const val ABERTO = "aberto"
    const val EM_CONTAGEM = "em_contagem"
    const val FINALIZADO = "finalizado"
    const val AJUSTADO = "ajustado"
    const val CANCELADO = "cancelado"
}

object StatusItemInventario {
    const val PENDENTE = "pendente"
    const val CONTADO = "contado"
    const val NAO_PREVISTO = "nao_previsto"
}
