package wms.backend.inventario

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.tenancy.TenantTx
import wms.backend.transferencia.LocaisProdutosRepository
import wms.backend.transferencia.ProdutosEstoqueTable
import wms.backend.transferencia.ResultadoValidacaoProduto
import wms.backend.transferencia.SaldosProdutoLocalTable
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

const val TAMANHO_PAGINA_INVENTARIOS = 20

sealed class ResultadoContagem {
    data class Ok(val item: ItemInventarioDto) : ResultadoContagem()
    object InventarioNaoEncontrado : ResultadoContagem()
    object InventarioEncerrado : ResultadoContagem()
    object ProdutoNaoReconhecido : ResultadoContagem()
    object QuantidadeInvalida : ResultadoContagem()
}

sealed class ResultadoAprovacao {
    object NaoEncontrado : ResultadoAprovacao()
    object ItensPendentes : ResultadoAprovacao()
    data class Ok(val avisoRecontagem: String?) : ResultadoAprovacao()
}

data class ResultadoListaInventarios(
    val itens: List<InventarioListDto>,
    val paginaAtual: Int,
    val totalPaginas: Int,
    val totalRegistros: Int,
)

object InventarioRepository {

    /**
     * Resolve o escopo contra o cadastro STUB já usado em Transferência
     * (app.saldos_produto_local) — mesma ressalva de LocaisProdutosRepository:
     * sem integração Sankhya real ainda. 'grupo_produto' é uma simplificação
     * v1 (não existe conceito de grupo no stub hoje): trata como "todos os
     * produtos com saldo cadastrado".
     */
    private fun resolverEscopo(tenantId: UUID, escopoTipo: String, escopoValores: List<String>): List<Triple<String, String, BigDecimal>> {
        val query = SaldosProdutoLocalTable.selectAll().where { SaldosProdutoLocalTable.tenantId eq tenantId }
        val linhas = when (escopoTipo) {
            "local" -> query.andWhere { SaldosProdutoLocalTable.codigoLocal inList escopoValores }
            "produtos_especificos" -> query.andWhere { SaldosProdutoLocalTable.codigoProduto inList escopoValores }
            else -> query // grupo_produto (simplificação v1) — todo o cadastro
        }
        return linhas.map {
            Triple(
                it[SaldosProdutoLocalTable.codigoProduto],
                it[SaldosProdutoLocalTable.codigoLocal],
                it[SaldosProdutoLocalTable.saldo],
            )
        }
    }

    fun abrir(tenantId: UUID, descricao: String, escopoTipo: String, escopoValores: List<String>, userId: UUID, nome: String): InventarioCriadoDto =
        TenantTx.run(tenantId) {
            val id = UUID.randomUUID()
            val agora = Instant.now()

            InventariosTable.insert {
                it[InventariosTable.id] = id
                it[InventariosTable.tenantId] = tenantId
                it[InventariosTable.descricao] = descricao
                it[InventariosTable.escopoTipo] = escopoTipo
                it[InventariosTable.escopoValores] = Json.encodeToString(escopoValores)
                it[status] = InventarioStatus.ABERTO
                it[abertoPorUserId] = userId
                it[abertoPorNome] = nome
                it[abertoEm] = agora
                it[pendenteWriteBack] = false
            }

            val produtosPorCodigo = ProdutosEstoqueTable.selectAll()
                .where { ProdutosEstoqueTable.tenantId eq tenantId }
                .associateBy { it[ProdutosEstoqueTable.codigo] }

            resolverEscopo(tenantId, escopoTipo, escopoValores).forEach { (produtoCodigo, local, saldo) ->
                val ctrl = produtosPorCodigo[produtoCodigo]?.get(ProdutosEstoqueTable.ctrl) ?: ""
                InventarioItensTable.insert {
                    it[InventarioItensTable.id] = UUID.randomUUID()
                    it[InventarioItensTable.tenantId] = tenantId
                    it[inventarioId] = id
                    it[InventarioItensTable.produtoCodigo] = produtoCodigo
                    it[controle] = ctrl
                    it[InventarioItensTable.local] = local
                    it[quantidadeSistema] = saldo
                    it[quantidadeContada] = BigDecimal.ZERO
                    it[statusItem] = StatusItemInventario.PENDENTE
                }
            }

            InventarioCriadoDto(id = id.toString(), status = InventarioStatus.ABERTO)
        }

    fun registrarContagem(
        tenantId: UUID,
        inventarioId: UUID,
        local: String,
        codigoLido: String,
        quantidadeInformada: String?,
        userId: UUID,
        nome: String,
        canalOrigem: String,
    ): ResultadoContagem = TenantTx.run(tenantId) {
        val inventario = InventariosTable.selectAll()
            .where { (InventariosTable.tenantId eq tenantId) and (InventariosTable.id eq inventarioId) }
            .singleOrNull() ?: return@run ResultadoContagem.InventarioNaoEncontrado

        val statusAtual = inventario[InventariosTable.status]
        if (statusAtual != InventarioStatus.ABERTO && statusAtual != InventarioStatus.EM_CONTAGEM) {
            return@run ResultadoContagem.InventarioEncerrado
        }
        // Primeira contagem transiciona 'aberto' -> 'em_contagem' automaticamente — não faz
        // sentido o coletor esperar um clique manual no desktop pra poder começar a bipar.
        if (statusAtual == InventarioStatus.ABERTO) {
            InventariosTable.update({ (InventariosTable.tenantId eq tenantId) and (InventariosTable.id eq inventarioId) }) {
                it[status] = InventarioStatus.EM_CONTAGEM
            }
        }

        // Sem comparador de saldo aqui (local = null) — contagem precisa funcionar mesmo
        // quando o sistema acha que não tem saldo ali (é exatamente esse tipo de divergência
        // que uma auditoria de estoque existe pra encontrar).
        val resultado = LocaisProdutosRepository.validarProduto(tenantId, codigoLido, local = null)
        val produto = when (resultado) {
            is ResultadoValidacaoProduto.Ok -> resultado.produto
            else -> return@run ResultadoContagem.ProdutoNaoReconhecido
        }

        val quantidadeParseada = quantidadeInformada?.trim()?.replace(",", ".")?.toBigDecimalOrNull()
        if (produto.modo == "bulk" && quantidadeParseada == null) return@run ResultadoContagem.QuantidadeInvalida
        val quantidade = quantidadeParseada
            ?: if (produto.modo == "labelqty") produto.qtdEtiqueta?.toBigDecimalOrNull() ?: BigDecimal.ONE else BigDecimal.ONE
        if (quantidade <= BigDecimal.ZERO) return@run ResultadoContagem.QuantidadeInvalida

        val existente = InventarioItensTable.selectAll()
            .where {
                (InventarioItensTable.tenantId eq tenantId) and
                    (InventarioItensTable.inventarioId eq inventarioId) and
                    (InventarioItensTable.produtoCodigo eq produto.codigo) and
                    (InventarioItensTable.controle eq produto.ctrl) and
                    (InventarioItensTable.local eq local)
            }
            .singleOrNull()

        val itemId: UUID
        val qtdFinal: BigDecimal
        val agora = Instant.now()

        if (existente != null) {
            itemId = existente[InventarioItensTable.id]
            qtdFinal = existente[InventarioItensTable.quantidadeContada] + quantidade
            InventarioItensTable.update({ InventarioItensTable.id eq itemId }) {
                it[quantidadeContada] = qtdFinal
                it[statusItem] = StatusItemInventario.CONTADO
                it[operadorContagemUserId] = userId
                it[operadorContagemNome] = nome
                it[contadoEm] = agora
                it[InventarioItensTable.canalOrigem] = canalOrigem
            }
        } else {
            // Item lido que não estava no escopo original — registra como "não previsto", não bloqueia.
            itemId = UUID.randomUUID()
            qtdFinal = quantidade
            val saldoAtual = SaldosProdutoLocalTable.selectAll()
                .where {
                    (SaldosProdutoLocalTable.tenantId eq tenantId) and
                        (SaldosProdutoLocalTable.codigoProduto eq produto.codigo) and
                        (SaldosProdutoLocalTable.codigoLocal eq local)
                }
                .singleOrNull()?.get(SaldosProdutoLocalTable.saldo) ?: BigDecimal.ZERO

            InventarioItensTable.insert {
                it[id] = itemId
                it[InventarioItensTable.tenantId] = tenantId
                it[InventarioItensTable.inventarioId] = inventarioId
                it[produtoCodigo] = produto.codigo
                it[controle] = produto.ctrl
                it[InventarioItensTable.local] = local
                it[quantidadeSistema] = saldoAtual
                it[quantidadeContada] = qtdFinal
                it[statusItem] = StatusItemInventario.NAO_PREVISTO
                it[operadorContagemUserId] = userId
                it[operadorContagemNome] = nome
                it[contadoEm] = agora
                it[InventarioItensTable.canalOrigem] = canalOrigem
            }
        }

        ResultadoContagem.Ok(
            ItemInventarioDto(
                id = itemId.toString(),
                produtoCodigo = produto.codigo,
                nomeProduto = produto.nome,
                controle = produto.ctrl,
                local = local,
                quantidadeSistema = (existente?.get(InventarioItensTable.quantidadeSistema) ?: BigDecimal.ZERO).toPlainString(),
                quantidadeContada = qtdFinal.toPlainString(),
                divergencia = (qtdFinal - (existente?.get(InventarioItensTable.quantidadeSistema) ?: BigDecimal.ZERO)).toPlainString(),
                statusItem = if (existente != null) StatusItemInventario.CONTADO else StatusItemInventario.NAO_PREVISTO,
                canalOrigem = canalOrigem,
            ),
        )
    }

    fun listar(tenantId: UUID, status: String?, busca: String?, pagina: Int): ResultadoListaInventarios = TenantTx.run(tenantId) {
        var condicao: Op<Boolean> = InventariosTable.tenantId eq tenantId
        if (!status.isNullOrBlank()) condicao = condicao and (InventariosTable.status eq status)
        if (!busca.isNullOrBlank()) {
            val termo = "%${busca.trim().lowercase()}%"
            condicao = condicao and (
                (InventariosTable.descricao.lowerCase() like termo) or (InventariosTable.abertoPorNome.lowerCase() like termo)
                )
        }

        val totalRegistros = InventariosTable.selectAll().where { condicao }.count().toInt()
        val totalPaginas = maxOf(1, (totalRegistros + TAMANHO_PAGINA_INVENTARIOS - 1) / TAMANHO_PAGINA_INVENTARIOS)
        val paginaAtual = pagina.coerceIn(1, totalPaginas)

        val inventarios = InventariosTable.selectAll()
            .where { condicao }
            .orderBy(InventariosTable.abertoEm, SortOrder.DESC)
            .limit(TAMANHO_PAGINA_INVENTARIOS, offset = ((paginaAtual - 1) * TAMANHO_PAGINA_INVENTARIOS).toLong())
            .toList()

        val itens = inventarios.map { inv ->
            val invId = inv[InventariosTable.id]
            val itensDoInventario = InventarioItensTable.selectAll()
                .where { (InventarioItensTable.tenantId eq tenantId) and (InventarioItensTable.inventarioId eq invId) }
                .toList()
            val contados = itensDoInventario.count { it[InventarioItensTable.statusItem] != StatusItemInventario.PENDENTE }

            InventarioListDto(
                id = invId.toString(),
                descricao = inv[InventariosTable.descricao],
                status = inv[InventariosTable.status],
                abertoEm = DateTimeFormatter.ISO_INSTANT.format(inv[InventariosTable.abertoEm]),
                abertoPorNome = inv[InventariosTable.abertoPorNome],
                totalItens = itensDoInventario.size,
                itensContados = contados,
            )
        }

        ResultadoListaInventarios(itens = itens, paginaAtual = paginaAtual, totalPaginas = totalPaginas, totalRegistros = totalRegistros)
    }

    fun detalhe(tenantId: UUID, inventarioId: UUID): InventarioDetalheDto? = TenantTx.run(tenantId) {
        val inv = InventariosTable.selectAll()
            .where { (InventariosTable.tenantId eq tenantId) and (InventariosTable.id eq inventarioId) }
            .singleOrNull() ?: return@run null

        val itens = itensComDivergencia(tenantId, inventarioId)

        InventarioDetalheDto(
            id = inventarioId.toString(),
            descricao = inv[InventariosTable.descricao],
            status = inv[InventariosTable.status],
            escopoTipo = inv[InventariosTable.escopoTipo],
            escopoValores = Json.decodeFromString(inv[InventariosTable.escopoValores]),
            abertoPorNome = inv[InventariosTable.abertoPorNome],
            abertoEm = DateTimeFormatter.ISO_INSTANT.format(inv[InventariosTable.abertoEm]),
            totalItens = itens.size,
            itensContados = itens.count { it.statusItem != StatusItemInventario.PENDENTE },
            itens = itens,
        )
    }

    fun divergencias(tenantId: UUID, inventarioId: UUID): List<ItemInventarioDto> = TenantTx.run(tenantId) {
        itensComDivergencia(tenantId, inventarioId).filter { it.quantidadeContada != it.quantidadeSistema }
    }

    private fun itensComDivergencia(tenantId: UUID, inventarioId: UUID): List<ItemInventarioDto> {
        val produtosPorCodigo = ProdutosEstoqueTable.selectAll()
            .where { ProdutosEstoqueTable.tenantId eq tenantId }
            .associateBy { it[ProdutosEstoqueTable.codigo] }

        return InventarioItensTable.selectAll()
            .where { (InventarioItensTable.tenantId eq tenantId) and (InventarioItensTable.inventarioId eq inventarioId) }
            .map {
                val qtdSistema = it[InventarioItensTable.quantidadeSistema]
                val qtdContada = it[InventarioItensTable.quantidadeContada]
                ItemInventarioDto(
                    id = it[InventarioItensTable.id].toString(),
                    produtoCodigo = it[InventarioItensTable.produtoCodigo],
                    nomeProduto = produtosPorCodigo[it[InventarioItensTable.produtoCodigo]]?.get(ProdutosEstoqueTable.nome) ?: it[InventarioItensTable.produtoCodigo],
                    controle = it[InventarioItensTable.controle],
                    local = it[InventarioItensTable.local],
                    quantidadeSistema = qtdSistema.toPlainString(),
                    quantidadeContada = qtdContada.toPlainString(),
                    divergencia = (qtdContada - qtdSistema).toPlainString(),
                    statusItem = it[InventarioItensTable.statusItem],
                    canalOrigem = it[InventarioItensTable.canalOrigem],
                )
            }
    }

    fun mudarStatus(tenantId: UUID, inventarioId: UUID, novoStatus: String): Boolean = TenantTx.run(tenantId) {
        val linhas = InventariosTable.update({ (InventariosTable.tenantId eq tenantId) and (InventariosTable.id eq inventarioId) }) {
            it[status] = novoStatus
            if (novoStatus == InventarioStatus.FINALIZADO || novoStatus == InventarioStatus.CANCELADO) {
                it[fechadoEm] = Instant.now()
            }
        }
        linhas > 0
    }

    /**
     * Bloqueia se houver item 'pendente'. Recalcula quantidade_sistema contra o saldo ATUAL
     * (fica no lugar do "TGFEST ao vivo" real) — se mudou desde a abertura, avisa
     * explicitamente antes de aprovar (regra de recontagem).
     */
    fun aprovar(tenantId: UUID, inventarioId: UUID, userId: UUID, nome: String): ResultadoAprovacao = TenantTx.run(tenantId) {
        val inv = InventariosTable.selectAll()
            .where { (InventariosTable.tenantId eq tenantId) and (InventariosTable.id eq inventarioId) }
            .singleOrNull() ?: return@run ResultadoAprovacao.NaoEncontrado

        val itens = InventarioItensTable.selectAll()
            .where { (InventarioItensTable.tenantId eq tenantId) and (InventarioItensTable.inventarioId eq inventarioId) }
            .toList()
        if (itens.any { it[InventarioItensTable.statusItem] == StatusItemInventario.PENDENTE }) {
            return@run ResultadoAprovacao.ItensPendentes
        }

        var houveMudanca = false
        itens.forEach { item ->
            val saldoAtual = SaldosProdutoLocalTable.selectAll()
                .where {
                    (SaldosProdutoLocalTable.tenantId eq tenantId) and
                        (SaldosProdutoLocalTable.codigoProduto eq item[InventarioItensTable.produtoCodigo]) and
                        (SaldosProdutoLocalTable.codigoLocal eq item[InventarioItensTable.local])
                }
                .singleOrNull()?.get(SaldosProdutoLocalTable.saldo) ?: BigDecimal.ZERO

            if (saldoAtual != item[InventarioItensTable.quantidadeSistema]) {
                houveMudanca = true
                InventarioItensTable.update({ InventarioItensTable.id eq item[InventarioItensTable.id] }) {
                    it[quantidadeSistema] = saldoAtual
                }
            }
        }

        InventariosTable.update({ (InventariosTable.tenantId eq tenantId) and (InventariosTable.id eq inventarioId) }) {
            it[status] = InventarioStatus.AJUSTADO
            it[ajustadoPorUserId] = userId
            it[ajustadoPorNome] = nome
            it[ajustadoEm] = Instant.now()
            it[pendenteWriteBack] = true
        }

        ResultadoAprovacao.Ok(
            avisoRecontagem = if (houveMudanca) {
                "Houve movimentação de estoque durante o inventário — divergências recalculadas com saldo atualizado."
            } else null,
        )
    }

    fun marcarWriteBackConcluido(tenantId: UUID, inventarioId: UUID): Unit = TenantTx.run(tenantId) {
        InventariosTable.update({ (InventariosTable.tenantId eq tenantId) and (InventariosTable.id eq inventarioId) }) {
            it[pendenteWriteBack] = false
        }
        Unit
    }

    fun listarPendentesWriteBack(tenantId: UUID): List<UUID> = TenantTx.run(tenantId) {
        InventariosTable.selectAll()
            .where { (InventariosTable.tenantId eq tenantId) and (InventariosTable.pendenteWriteBack eq true) }
            .map { it[InventariosTable.id] }
    }
}
