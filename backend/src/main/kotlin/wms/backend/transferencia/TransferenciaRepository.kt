package wms.backend.transferencia

import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.tenancy.TenantTx
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

const val TAMANHO_PAGINA_TRANSFERENCIAS = 20

data class ResultadoListaTransferencias(
    val itens: List<TransferenciaListDto>,
    val paginaAtual: Int,
    val totalPaginas: Int,
    val totalRegistros: Int,
)

object TransferenciaRepository {

    fun buscarOrigem(tenantId: UUID, transferenciaId: UUID): String? = TenantTx.run(tenantId) {
        TransferenciasTable.selectAll()
            .where { (TransferenciasTable.tenantId eq tenantId) and (TransferenciasTable.id eq transferenciaId) }
            .singleOrNull()
            ?.get(TransferenciasTable.origem)
    }

    fun criar(tenantId: UUID, origem: String, destino: String, canalOrigem: String, operadorUserId: UUID, operadorNome: String): TransferenciaCriadaDto =
        TenantTx.run(tenantId) {
            val id = UUID.randomUUID()
            val agora = Instant.now()
            TransferenciasTable.insert {
                it[TransferenciasTable.id] = id
                it[TransferenciasTable.tenantId] = tenantId
                it[TransferenciasTable.origem] = origem
                it[TransferenciasTable.destino] = destino
                it[TransferenciasTable.canalOrigem] = canalOrigem
                it[status] = TransferenciaStatus.ABERTA
                it[TransferenciasTable.operadorUserId] = operadorUserId
                it[TransferenciasTable.operadorNome] = operadorNome
                it[pendenteWriteBack] = false
                it[criadoEm] = agora
                it[atualizadoEm] = agora
            }
            TransferenciaCriadaDto(id = id.toString(), origem = origem, destino = destino, status = TransferenciaStatus.ABERTA)
        }

    /** Soma na quantidade se já existe um item com o mesmo produto+controle nesta transferência (mesma regra do coletor). */
    fun adicionarItem(tenantId: UUID, transferenciaId: UUID, produto: ProdutoEstoqueDto, controle: String, quantidade: BigDecimal): ItemTransferenciaDto? =
        TenantTx.run(tenantId) {
            val transferencia = TransferenciasTable.selectAll()
                .where { (TransferenciasTable.tenantId eq tenantId) and (TransferenciasTable.id eq transferenciaId) }
                .singleOrNull() ?: return@run null
            if (transferencia[TransferenciasTable.status] != TransferenciaStatus.ABERTA) return@run null

            val existente = TransferenciaItensTable.selectAll()
                .where {
                    (TransferenciaItensTable.tenantId eq tenantId) and
                        (TransferenciaItensTable.transferenciaId eq transferenciaId) and
                        (TransferenciaItensTable.codigoProduto eq produto.codigo) and
                        (TransferenciaItensTable.controle eq controle)
                }
                .singleOrNull()

            val itemId: UUID
            val qtdFinal: BigDecimal
            if (existente != null) {
                itemId = existente[TransferenciaItensTable.id]
                qtdFinal = existente[TransferenciaItensTable.quantidade] + quantidade
                TransferenciaItensTable.update({ TransferenciaItensTable.id eq itemId }) {
                    it[TransferenciaItensTable.quantidade] = qtdFinal
                }
            } else {
                itemId = UUID.randomUUID()
                qtdFinal = quantidade
                TransferenciaItensTable.insert {
                    it[id] = itemId
                    it[TransferenciaItensTable.tenantId] = tenantId
                    it[TransferenciaItensTable.transferenciaId] = transferenciaId
                    it[codigoProduto] = produto.codigo
                    it[nomeProduto] = produto.nome
                    it[TransferenciaItensTable.controle] = controle
                    it[TransferenciaItensTable.quantidade] = qtdFinal
                    it[unidade] = produto.unidade
                    it[criadoEm] = Instant.now()
                }
            }

            tocarAtividade(tenantId, transferenciaId)

            ItemTransferenciaDto(
                id = itemId.toString(),
                codigoProduto = produto.codigo,
                nomeProduto = produto.nome,
                controle = controle,
                quantidade = qtdFinal.toPlainString(),
                unidade = produto.unidade,
            )
        }

    fun removerItem(tenantId: UUID, transferenciaId: UUID, itemId: UUID): Boolean = TenantTx.run(tenantId) {
        val linhas = TransferenciaItensTable.deleteWhere {
            (TransferenciaItensTable.tenantId eq tenantId) and
                (TransferenciaItensTable.transferenciaId eq transferenciaId) and
                (TransferenciaItensTable.id eq itemId)
        }
        if (linhas > 0) tocarAtividade(tenantId, transferenciaId)
        linhas > 0
    }

    private fun tocarAtividade(tenantId: UUID, transferenciaId: UUID) {
        TransferenciasTable.update({ (TransferenciasTable.tenantId eq tenantId) and (TransferenciasTable.id eq transferenciaId) }) {
            it[atualizadoEm] = Instant.now()
        }
    }

    /** Grava local imediatamente (status='confirmada') e marca pendente_write_back — a sincronização com o Sankhya acontece à parte (ver TransferenciaWriteBackQueue). */
    fun confirmar(tenantId: UUID, transferenciaId: UUID): Boolean = TenantTx.run(tenantId) {
        val linhas = TransferenciasTable.update({
            (TransferenciasTable.tenantId eq tenantId) and
                (TransferenciasTable.id eq transferenciaId) and
                (TransferenciasTable.status eq TransferenciaStatus.ABERTA)
        }) {
            it[status] = TransferenciaStatus.CONFIRMADA
            it[pendenteWriteBack] = true
            it[confirmadoEm] = Instant.now()
            it[atualizadoEm] = Instant.now()
        }
        linhas > 0
    }

    fun marcarWriteBackConcluido(tenantId: UUID, transferenciaId: UUID): Unit = TenantTx.run(tenantId) {
        TransferenciasTable.update({ (TransferenciasTable.tenantId eq tenantId) and (TransferenciasTable.id eq transferenciaId) }) {
            it[pendenteWriteBack] = false
        }
        Unit
    }

    fun listarPendentesWriteBack(tenantId: UUID): List<UUID> = TenantTx.run(tenantId) {
        TransferenciasTable.selectAll()
            .where { (TransferenciasTable.tenantId eq tenantId) and (TransferenciasTable.pendenteWriteBack eq true) }
            .map { it[TransferenciasTable.id] }
    }

    fun listar(tenantId: UUID, canal: String?, status: String?, busca: String?, pagina: Int): ResultadoListaTransferencias = TenantTx.run(tenantId) {
        var condicao: Op<Boolean> = TransferenciasTable.tenantId eq tenantId
        if (!canal.isNullOrBlank()) condicao = condicao and (TransferenciasTable.canalOrigem eq canal)
        if (!status.isNullOrBlank()) condicao = condicao and (TransferenciasTable.status eq status)
        if (!busca.isNullOrBlank()) {
            val termo = "%${busca.trim().lowercase()}%"
            condicao = condicao and (
                (TransferenciasTable.origem.lowerCase() like termo) or
                    (TransferenciasTable.destino.lowerCase() like termo) or
                    (TransferenciasTable.operadorNome.lowerCase() like termo)
                )
        }

        val totalRegistros = TransferenciasTable.selectAll().where { condicao }.count().toInt()
        val totalPaginas = maxOf(1, (totalRegistros + TAMANHO_PAGINA_TRANSFERENCIAS - 1) / TAMANHO_PAGINA_TRANSFERENCIAS)
        val paginaAtual = pagina.coerceIn(1, totalPaginas)

        val transferencias = TransferenciasTable.selectAll()
            .where { condicao }
            .orderBy(TransferenciasTable.criadoEm, SortOrder.DESC)
            .limit(TAMANHO_PAGINA_TRANSFERENCIAS, offset = ((paginaAtual - 1) * TAMANHO_PAGINA_TRANSFERENCIAS).toLong())
            .toList()

        val itens = transferencias.map { t ->
            val transferenciaId = t[TransferenciasTable.id]
            val itensDaTransferencia = TransferenciaItensTable.selectAll()
                .where { (TransferenciaItensTable.tenantId eq tenantId) and (TransferenciaItensTable.transferenciaId eq transferenciaId) }
                .toList()
            val totalUnidades = itensDaTransferencia.fold(BigDecimal.ZERO) { acc, item -> acc + item[TransferenciaItensTable.quantidade] }

            TransferenciaListDto(
                id = transferenciaId.toString(),
                criadoEm = DateTimeFormatter.ISO_INSTANT.format(t[TransferenciasTable.criadoEm]),
                origem = t[TransferenciasTable.origem],
                destino = t[TransferenciasTable.destino],
                totalItens = itensDaTransferencia.size,
                totalUnidades = totalUnidades.toPlainString(),
                canalOrigem = t[TransferenciasTable.canalOrigem],
                status = t[TransferenciasTable.status],
                operadorNome = t[TransferenciasTable.operadorNome],
            )
        }

        ResultadoListaTransferencias(itens = itens, paginaAtual = paginaAtual, totalPaginas = totalPaginas, totalRegistros = totalRegistros)
    }

    /** Rascunho 'aberta' sem nenhum item e sem atividade há mais de [minutosLimite] — ver TransferenciaAbandonoWorker. Retorna quantos foram marcados. */
    fun marcarAbandonadas(tenantId: UUID, minutosLimite: Long): Int = TenantTx.run(tenantId) {
        val limite = Instant.now().minusSeconds(minutosLimite * 60)
        val candidatas = TransferenciasTable.selectAll()
            .where { (TransferenciasTable.tenantId eq tenantId) and (TransferenciasTable.status eq TransferenciaStatus.ABERTA) and (TransferenciasTable.atualizadoEm less limite) }
            .map { it[TransferenciasTable.id] }

        var marcadas = 0
        candidatas.forEach { transferenciaId ->
            val temItem = TransferenciaItensTable.selectAll()
                .where { (TransferenciaItensTable.tenantId eq tenantId) and (TransferenciaItensTable.transferenciaId eq transferenciaId) }
                .limit(1)
                .any()
            if (!temItem) {
                TransferenciasTable.update({ (TransferenciasTable.tenantId eq tenantId) and (TransferenciasTable.id eq transferenciaId) }) {
                    it[status] = TransferenciaStatus.ABANDONADA
                }
                marcadas++
            }
        }
        marcadas
    }
}
