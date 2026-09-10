package wms.backend.separacao

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.produtos.CodigosBarraCacheTable
import wms.backend.produtos.ProdutosCacheTable
import wms.backend.tarefas.TarefasTable
import wms.backend.tenancy.TenantTx
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class ItemParaSalvar(
    val sequencia: Int,
    val codprod: Int,
    val controle: String,
    val codvol: String?,
    val qtdNeg: BigDecimal,
    val qtdEntregue: BigDecimal,
    val dadosJson: String,
    /** TGFVOL.UTILICONFPESO do codvol — exige pesagem na bipagem (rotina de peso, portada do projeto base). */
    val usaConfPeso: Boolean = false,
    /** Unidades alternativas (TGFVOA), match por linha — só p/ display "Pedido: X CX". */
    val unidadeComercial: String? = null,
    val unidadePadrao: String? = null,
    val divideMultiplica: String? = null,
    val fatorConversao: BigDecimal? = null,
    /** TGFPRO.AD_TIPOSEPARACAO — 1 Secos | 2 Resfriados | 3 Congelados (default 1). Conferência por etapa (V29). */
    val tipoSeparacao: Short = 1,
)

data class UmaParaSalvar(
    val codprod: Int,
    val coduma: Int,
    val descricao: String?,
    val peso: BigDecimal?,
    val codvol: String?,
    val codbarra: String?,
    val padrao: Boolean,
)

data class CodigoBarraParaSalvar(
    val codigoBarra: String,
    val codprod: Int,
    val codvol: String?,
    val controle: String,
    val origem: String,
    val quantidade: BigDecimal? = null,
    val divideMultiplica: String? = null,
)

class SessaoJaAtivaException(val sessaoId: UUID) : Exception("Já existe uma sessão de separação ativa para esta nota")

object SeparacaoRepository {

    /**
     * Cria a sessão local IMEDIATAMENTE (não espera o Sankhya) — o
     * carregamento dos itens acontece depois, em background (ver
     * SeparacaoService.iniciar). Se já existe sessão ativa (carregando ou
     * pronta) pra essa nota, lança [SessaoJaAtivaException] com o id dela —
     * mesma ideia do "iniciar-conferencia" do projeto base, que não recria
     * se já existe.
     */
    fun criarSessao(tenantId: UUID, nunota: Long): UUID = TenantTx.run(tenantId) {
        val ativa = SeparacaoSessoesTable.selectAll()
            .where {
                (SeparacaoSessoesTable.tenantId eq tenantId) and
                    (SeparacaoSessoesTable.nunota eq nunota.toInt()) and
                    (SeparacaoSessoesTable.status inList listOf(SeparacaoStatus.CARREGANDO, SeparacaoStatus.PRONTA))
            }
            .singleOrNull()
        if (ativa != null) throw SessaoJaAtivaException(ativa[SeparacaoSessoesTable.id])

        val tarefa = TarefasTable.selectAll()
            .where { (TarefasTable.tenantId eq tenantId) and (TarefasTable.nunota eq nunota.toInt()) }
            .singleOrNull()

        val agora = Instant.now()
        val id = UUID.randomUUID()
        SeparacaoSessoesTable.insert {
            it[SeparacaoSessoesTable.id] = id
            it[SeparacaoSessoesTable.tenantId] = tenantId
            it[SeparacaoSessoesTable.nunota] = nunota.toInt()
            it[tarefaId] = tarefa?.get(TarefasTable.id)
            it[status] = SeparacaoStatus.CARREGANDO
            it[statusOperacionalSnapshot] = tarefa?.get(TarefasTable.statusOperacional) ?: "aguardando"
            it[buscarCodigoBarraPor] = "A"
            it[criadoEm] = agora
            it[atualizadoEm] = agora
        }
        id
    }

    fun marcarPronta(
        tenantId: UUID,
        sessaoId: UUID,
        fingerprint: String,
        buscarCodigoBarraPor: String,
        qtdAmaior: String?,
        obterQtdBalanca: String?,
        produtosForaPed: String?,
        conferenciaSegmentada: Boolean,
        fatAoConcluir: String?,
        exibirProd: String?,
        exibirQtd: String?,
        exibirProdConf: String?,
        exibirQtdConf: String?,
        exibirImgProd: String?,
    ): Unit = TenantTx.run(tenantId) {
        SeparacaoSessoesTable.update({ (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }) {
            it[status] = SeparacaoStatus.PRONTA
            it[fingerprintItens] = fingerprint
            it[SeparacaoSessoesTable.buscarCodigoBarraPor] = buscarCodigoBarraPor
            it[SeparacaoSessoesTable.qtdAmaior] = qtdAmaior
            it[SeparacaoSessoesTable.obterQtdBalanca] = obterQtdBalanca
            it[SeparacaoSessoesTable.produtosForaPed] = produtosForaPed
            it[SeparacaoSessoesTable.conferenciaSegmentada] = conferenciaSegmentada
            it[SeparacaoSessoesTable.fatAoConcluir] = fatAoConcluir
            it[SeparacaoSessoesTable.exibirProd] = exibirProd
            it[SeparacaoSessoesTable.exibirQtd] = exibirQtd
            it[SeparacaoSessoesTable.exibirProdConf] = exibirProdConf
            it[SeparacaoSessoesTable.exibirQtdConf] = exibirQtdConf
            it[SeparacaoSessoesTable.exibirImgProd] = exibirImgProd
            it[atualizadoEm] = Instant.now()
        }
        Unit
    }

    /** 'N' (ou ausente) = não obter peso pela balança; qualquer outro valor = fluxo de peso ativo pros itens usaConfPeso. */
    fun buscarObterQtdBalanca(tenantId: UUID, sessaoId: UUID): String? = TenantTx.run(tenantId) {
        SeparacaoSessoesTable.selectAll()
            .where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }
            .singleOrNull()
            ?.get(SeparacaoSessoesTable.obterQtdBalanca)
    }

    /** true = pode bipar mais que o negociado (CCO QTDAMAIOR='D'); false = bloqueia o excesso na hora da bipagem. */
    fun permiteQtdMaior(tenantId: UUID, sessaoId: UUID): Boolean = TenantTx.run(tenantId) {
        SeparacaoSessoesTable.selectAll()
            .where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }
            .singleOrNull()
            ?.get(SeparacaoSessoesTable.qtdAmaior) == "D"
    }

    /** Chamado logo após ConferenciaSP.salvarCabecalhoConferencia ter sucesso, ainda no carregamento em background. */
    fun salvarNuconf(tenantId: UUID, sessaoId: UUID, nuconf: Int): Unit = TenantTx.run(tenantId) {
        SeparacaoSessoesTable.update({ (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }) {
            it[SeparacaoSessoesTable.nuconf] = nuconf
            it[atualizadoEm] = Instant.now()
        }
        Unit
    }

    /** null se a sessão não existe ou o carregamento ainda não descobriu o NUCONF. */
    fun buscarNuconf(tenantId: UUID, sessaoId: UUID): Int? = TenantTx.run(tenantId) {
        SeparacaoSessoesTable.selectAll()
            .where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }
            .singleOrNull()
            ?.get(SeparacaoSessoesTable.nuconf)
    }

    /**
     * Recontagem — zera tudo que foi bipado e devolve a sessão pra 'pronta',
     * mesma ideia do "Realizar recontagem" do projeto base. NÃO apaga os
     * itens/códigos de barra (não mudaram), só o que foi conferido.
     */
    fun reiniciarContagem(tenantId: UUID, sessaoId: UUID): Unit = TenantTx.run(tenantId) {
        SeparacaoLeiturasTable.deleteWhere { (SeparacaoLeiturasTable.tenantId eq tenantId) and (SeparacaoLeiturasTable.sessaoId eq sessaoId) }
        SeparacaoItensTable.update({ (SeparacaoItensTable.tenantId eq tenantId) and (SeparacaoItensTable.sessaoId eq sessaoId) }) {
            it[qtdConferidaLocal] = BigDecimal.ZERO
        }
        SeparacaoSessoesTable.update({ (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }) {
            it[status] = SeparacaoStatus.PRONTA
            it[atualizadoEm] = Instant.now()
        }
        Unit
    }

    /**
     * Cancela as sessões locais ATIVAS (carregando/pronta) das notas dadas —
     * usado pelo sync quando o Sankhya volta a nota pra 'aguardando' (conferência
     * excluída/reaberta): a sessão local (com etapas já concluídas etc.) está
     * obsoleta, e o próximo `iniciar` precisa criar uma limpa. Retorna quantas cancelou.
     */
    fun cancelarSessoesAtivasPorNotas(tenantId: UUID, nunotas: List<Long>): Int = TenantTx.run(tenantId) {
        if (nunotas.isEmpty()) return@run 0
        SeparacaoSessoesTable.update({
            (SeparacaoSessoesTable.tenantId eq tenantId) and
                (SeparacaoSessoesTable.nunota inList nunotas.map { it.toInt() }) and
                (SeparacaoSessoesTable.status inList listOf(SeparacaoStatus.CARREGANDO, SeparacaoStatus.PRONTA))
        }) {
            it[status] = SeparacaoStatus.CANCELADA
            it[atualizadoEm] = Instant.now()
        }
    }

    /** Cancela a sessão local — usado só depois que o Sankhya já confirmou a desistência (ver SeparacaoService.cancelar). */
    fun marcarCancelada(tenantId: UUID, sessaoId: UUID): Unit = TenantTx.run(tenantId) {
        SeparacaoSessoesTable.update({ (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }) {
            it[status] = SeparacaoStatus.CANCELADA
            it[atualizadoEm] = Instant.now()
        }
        Unit
    }

    fun marcarConcluida(tenantId: UUID, sessaoId: UUID): Unit = TenantTx.run(tenantId) {
        SeparacaoSessoesTable.update({ (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }) {
            it[status] = SeparacaoStatus.CONCLUIDA
            it[atualizadoEm] = Instant.now()
        }
        Unit
    }

    fun marcarErro(tenantId: UUID, sessaoId: UUID, mensagem: String): Unit = TenantTx.run(tenantId) {
        SeparacaoSessoesTable.update({ (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }) {
            it[status] = SeparacaoStatus.ERRO
            it[erro] = mensagem.take(500)
            it[atualizadoEm] = Instant.now()
        }
        Unit
    }

    fun salvarItens(tenantId: UUID, sessaoId: UUID, itens: List<ItemParaSalvar>): Unit = TenantTx.run(tenantId) {
        if (itens.isEmpty()) return@run
        SeparacaoItensTable.batchInsert(itens) { item ->
            this[SeparacaoItensTable.id] = UUID.randomUUID()
            this[SeparacaoItensTable.tenantId] = tenantId
            this[SeparacaoItensTable.sessaoId] = sessaoId
            this[SeparacaoItensTable.sequencia] = item.sequencia
            this[SeparacaoItensTable.codprod] = item.codprod
            this[SeparacaoItensTable.controle] = item.controle
            this[SeparacaoItensTable.codvol] = item.codvol
            this[SeparacaoItensTable.qtdNeg] = item.qtdNeg
            this[SeparacaoItensTable.qtdEntregue] = item.qtdEntregue
            this[SeparacaoItensTable.qtdConferidaLocal] = BigDecimal.ZERO
            this[SeparacaoItensTable.usaConfPeso] = item.usaConfPeso
            this[SeparacaoItensTable.foraPedido] = false
            this[SeparacaoItensTable.unidadeComercial] = item.unidadeComercial
            this[SeparacaoItensTable.unidadePadrao] = item.unidadePadrao
            this[SeparacaoItensTable.divideMultiplica] = item.divideMultiplica
            this[SeparacaoItensTable.fatorConversao] = item.fatorConversao
            this[SeparacaoItensTable.tipoSeparacao] = item.tipoSeparacao
            this[SeparacaoItensTable.dados] = item.dadosJson
        }
        Unit
    }

    fun salvarUma(tenantId: UUID, sessaoId: UUID, umas: List<UmaParaSalvar>): Unit = TenantTx.run(tenantId) {
        if (umas.isEmpty()) return@run
        SeparacaoUmaTable.batchInsert(umas) { uma ->
            this[SeparacaoUmaTable.id] = UUID.randomUUID()
            this[SeparacaoUmaTable.tenantId] = tenantId
            this[SeparacaoUmaTable.sessaoId] = sessaoId
            this[SeparacaoUmaTable.codprod] = uma.codprod
            this[SeparacaoUmaTable.coduma] = uma.coduma
            this[SeparacaoUmaTable.descricao] = uma.descricao
            this[SeparacaoUmaTable.peso] = uma.peso
            this[SeparacaoUmaTable.codvol] = uma.codvol
            this[SeparacaoUmaTable.codbarra] = uma.codbarra
            this[SeparacaoUmaTable.padrao] = uma.padrao
        }
        Unit
    }

    fun listarUma(tenantId: UUID, sessaoId: UUID): List<UmaDto> = TenantTx.run(tenantId) {
        SeparacaoUmaTable.selectAll()
            .where { (SeparacaoUmaTable.tenantId eq tenantId) and (SeparacaoUmaTable.sessaoId eq sessaoId) }
            .map {
                UmaDto(
                    codprod = it[SeparacaoUmaTable.codprod],
                    coduma = it[SeparacaoUmaTable.coduma],
                    descricao = it[SeparacaoUmaTable.descricao],
                    peso = it[SeparacaoUmaTable.peso]?.toPlainString(),
                    codvol = it[SeparacaoUmaTable.codvol],
                    codbarra = it[SeparacaoUmaTable.codbarra],
                    padrao = it[SeparacaoUmaTable.padrao],
                )
            }
    }

    fun salvarCodigosBarra(tenantId: UUID, sessaoId: UUID, codigos: List<CodigoBarraParaSalvar>): Unit = TenantTx.run(tenantId) {
        if (codigos.isEmpty()) return@run
        SeparacaoCodigosBarraTable.batchInsert(codigos) { c ->
            this[SeparacaoCodigosBarraTable.id] = UUID.randomUUID()
            this[SeparacaoCodigosBarraTable.tenantId] = tenantId
            this[SeparacaoCodigosBarraTable.sessaoId] = sessaoId
            this[SeparacaoCodigosBarraTable.codigoBarra] = c.codigoBarra
            this[SeparacaoCodigosBarraTable.codprod] = c.codprod
            this[SeparacaoCodigosBarraTable.codvol] = c.codvol
            this[SeparacaoCodigosBarraTable.controle] = c.controle
            this[SeparacaoCodigosBarraTable.origem] = c.origem
            this[SeparacaoCodigosBarraTable.quantidade] = c.quantidade
            this[SeparacaoCodigosBarraTable.divideMultiplica] = c.divideMultiplica
        }
        Unit
    }

    fun listarCodigosBarra(tenantId: UUID, sessaoId: UUID): List<CodigoBarraDto> = TenantTx.run(tenantId) {
        SeparacaoCodigosBarraTable.selectAll()
            .where { (SeparacaoCodigosBarraTable.tenantId eq tenantId) and (SeparacaoCodigosBarraTable.sessaoId eq sessaoId) }
            .map {
                CodigoBarraDto(
                    codigoBarra = it[SeparacaoCodigosBarraTable.codigoBarra],
                    codprod = it[SeparacaoCodigosBarraTable.codprod],
                    codvol = it[SeparacaoCodigosBarraTable.codvol],
                    controle = it[SeparacaoCodigosBarraTable.controle],
                    origem = it[SeparacaoCodigosBarraTable.origem],
                )
            }
    }

    /**
     * Resolução de código de barras — 5 modos reais do Sankhya (campo
     * `ConfiguracaoConferencia.BUSCARCODBARRAPOR`), portados de
     * `SessaoService.resolverCodigoBarras` do projeto base:
     *
     * - A (Automático): EST → VOA → BAR com unidade → BAR sem unidade →
     *   Referência → Controle. Cada fonte só é usada se o item correspondente
     *   ainda existir na sessão (produto pode ter código cadastrado mas não
     *   fazer parte desta nota).
     * - C (Código do produto): o "código de barras" bipado É o CODPROD.
     * - R (Referência): produto.REFERENCIA, com fallback pra BAR genérico.
     * - U (Unidade alternativa): só VOA, ou BAR cuja unidade seja DIFERENTE
     *   da unidade base do produto (senão seria a unidade "normal").
     * - E (Estoque): só EST, com fallback casando direto pelo texto do controle.
     *
     * Pura (sem I/O) de propósito — [resolverCodigoBarras] e [conferirBipe]
     * chamam com dados já carregados, pra não repetir a mesma leitura do
     * banco duas vezes num fluxo que faz as duas coisas juntas.
     */
    private fun resolverContra(
        itens: List<ItemLocalResolucao>,
        codigos: List<CodigoBarraParaSalvar>,
        regra: String,
        codigoBarraLido: String,
    ): ItemResolvido? {
        // O parâmetro `controle` (não `item.controle`!) é o que cada branch
        // decide — só EST e "bipou o texto do controle direto" sabem de
        // verdade qual controle é; os outros (VOA/BAR/referência) devolvem
        // " " (em branco) de propósito, porque a resolução ali só identifica
        // o PRODUTO — o controle continua sendo escolha do operador.
        fun buildResult(item: ItemLocalResolucao, codvol: String?, controle: String, fator: BigDecimal?, divideMult: String?) =
            ItemResolvido(
                codprod = item.codprod,
                descricaoProduto = item.descricao,
                referencia = item.referencia,
                codvol = codvol ?: item.codvol,
                controle = controle,
                fatorConversao = fator?.toPlainString(),
                divideMultiplica = divideMult,
            )

        fun findItem(codprod: Int, controle: String?): ItemLocalResolucao? {
            val norm = controle?.trim()?.takeIf { it.isNotEmpty() } ?: " "
            if (norm != " ") {
                return itens.find { it.codprod == codprod && it.controle == norm } ?: itens.find { it.codprod == codprod }
            }
            return itens.find { it.codprod == codprod }
        }

        fun findCodigo(origem: String, extra: ((CodigoBarraParaSalvar) -> Boolean)? = null): CodigoBarraParaSalvar? =
            codigos.find { it.codigoBarra == codigoBarraLido && it.origem == origem && (extra == null || extra(it)) }

        fun voaFatorPara(codprod: Int, codvol: String?): CodigoBarraParaSalvar? =
            codigos.find { it.codprod == codprod && it.codvol == codvol && it.origem == "VOA" }

        fun controleEst(est: CodigoBarraParaSalvar): String = est.controle.trim().ifEmpty { " " }

        fun itemPorReferencia(): ItemLocalResolucao? =
            itens.find { it.referencia?.trim()?.isNotEmpty() == true && it.referencia.trim() == codigoBarraLido.trim() }

        fun itemPorControleDigitado(): ItemLocalResolucao? =
            itens.find { it.controle.trim().isNotEmpty() && it.controle.trim() == codigoBarraLido.trim() }

        return when (regra) {
            "C" -> {
                val codprod = codigoBarraLido.trim().toIntOrNull()
                val item = codprod?.let { cp -> itens.find { it.codprod == cp } }
                item?.let { buildResult(it, null, " ", null, null) }
            }

            "R" -> {
                itemPorReferencia()?.let { buildResult(it, null, " ", null, null) }
                    ?: findCodigo("BAR")?.let { bar -> findItem(bar.codprod, null)?.let { buildResult(it, null, " ", null, null) } }
            }

            "U" -> {
                val voa = findCodigo("VOA")
                val itemVoa = voa?.let { findItem(it.codprod, null) }
                if (voa != null && itemVoa != null) {
                    buildResult(itemVoa, voa.codvol, " ", voa.quantidade, voa.divideMultiplica)
                } else {
                    val barComUnit = findCodigo("BAR") { it.codvol != null }
                    val itemBar = barComUnit?.let { findItem(it.codprod, null) }
                    if (barComUnit != null && itemBar != null && barComUnit.codvol != itemBar.codvol) {
                        val fator = voaFatorPara(barComUnit.codprod, barComUnit.codvol)
                        buildResult(itemBar, barComUnit.codvol, " ", fator?.quantidade, fator?.divideMultiplica)
                    } else {
                        null
                    }
                }
            }

            "E" -> {
                val est = findCodigo("EST")
                val itemEst = est?.let { findItem(it.codprod, controleEst(it)) }
                if (est != null && itemEst != null) {
                    buildResult(itemEst, null, controleEst(est), null, null)
                } else {
                    itemPorControleDigitado()?.let { buildResult(it, null, it.controle, null, null) }
                }
            }

            else -> { // "A" — Automático
                val est = findCodigo("EST")
                val itemEst = est?.let { findItem(it.codprod, controleEst(it)) }
                if (est != null && itemEst != null) return buildResult(itemEst, null, controleEst(est), null, null)

                val voa = findCodigo("VOA")
                val itemVoa = voa?.let { findItem(it.codprod, null) }
                if (voa != null && itemVoa != null) return buildResult(itemVoa, voa.codvol, " ", voa.quantidade, voa.divideMultiplica)

                val barComUnit = findCodigo("BAR") { it.codvol != null }
                val itemBarComUnit = barComUnit?.let { findItem(it.codprod, null) }
                if (barComUnit != null && itemBarComUnit != null) {
                    val fator = voaFatorPara(barComUnit.codprod, barComUnit.codvol)
                    return buildResult(itemBarComUnit, barComUnit.codvol, " ", fator?.quantidade, fator?.divideMultiplica)
                }

                val barSemUnit = findCodigo("BAR") { it.codvol == null }
                val itemBarSemUnit = barSemUnit?.let { findItem(it.codprod, null) }
                if (barSemUnit != null && itemBarSemUnit != null) return buildResult(itemBarSemUnit, null, " ", null, null)

                itemPorReferencia()?.let { return buildResult(it, null, " ", null, null) }
                itemPorControleDigitado()?.let { buildResult(it, null, it.controle, null, null) }
            }
        }
    }

    private fun carregarItensLocais(tenantId: UUID, sessaoId: UUID): List<ItemLocalResolucao> =
        SeparacaoItensTable.selectAll()
            .where { (SeparacaoItensTable.tenantId eq tenantId) and (SeparacaoItensTable.sessaoId eq sessaoId) }
            .map { row ->
                val dados = runCatching { Json.parseToJsonElement(row[SeparacaoItensTable.dados]) as JsonObject }.getOrNull()
                ItemLocalResolucao(
                    codprod = row[SeparacaoItensTable.codprod],
                    controle = row[SeparacaoItensTable.controle],
                    codvol = row[SeparacaoItensTable.codvol],
                    descricao = dados?.get("Produto.DESCRPROD")?.jsonPrimitive?.contentOrNull,
                    referencia = dados?.get("Produto.REFERENCIA")?.jsonPrimitive?.contentOrNull,
                    tipControle = dados?.get("Produto.TIPCONTEST")?.jsonPrimitive?.contentOrNull,
                    lisControles = dados?.get("Produto.LISCONTEST")?.jsonPrimitive?.contentOrNull,
                    usaConfPeso = row[SeparacaoItensTable.usaConfPeso],
                )
            }

    private fun carregarCodigosLocais(tenantId: UUID, sessaoId: UUID): List<CodigoBarraParaSalvar> =
        SeparacaoCodigosBarraTable.selectAll()
            .where { (SeparacaoCodigosBarraTable.tenantId eq tenantId) and (SeparacaoCodigosBarraTable.sessaoId eq sessaoId) }
            .map { row ->
                CodigoBarraParaSalvar(
                    codigoBarra = row[SeparacaoCodigosBarraTable.codigoBarra],
                    codprod = row[SeparacaoCodigosBarraTable.codprod],
                    codvol = row[SeparacaoCodigosBarraTable.codvol],
                    controle = row[SeparacaoCodigosBarraTable.controle],
                    origem = row[SeparacaoCodigosBarraTable.origem],
                    quantidade = row[SeparacaoCodigosBarraTable.quantidade],
                    divideMultiplica = row[SeparacaoCodigosBarraTable.divideMultiplica],
                )
            }

    /**
     * Produto não constante no pedido (PRODUTOSFORAPED='D' na CCO) — busca no
     * catálogo local completo (produtos_cache/codigos_barra_cache, sincronizado
     * por ProdutoCatalogoSyncWorker, NÃO nos itens da nota) e inclui como item
     * novo na sessão (qtd_neg=0 — nada foi negociado, fica divergente por
     * definição; a CCO já decidiu aceitar isso ao permitir chegar aqui).
     * `dados` só carrega TIPCONTEST/LISCONTEST (o resto do fluxo já lê só isso
     * do JSON pra produto fora do pedido).
     */
    private fun incluirProdutoForaPedido(tenantId: UUID, sessaoId: UUID, codigoBarraLido: String, tipoSeparacao: Short): ItemLocalResolucao? {
        val codigo = codigoBarraLido.trim()
        val codprod = codigo.toIntOrNull()
            ?: CodigosBarraCacheTable.selectAll()
                .where { (CodigosBarraCacheTable.tenantId eq tenantId) and (CodigosBarraCacheTable.codbarra eq codigo) }
                .firstOrNull()
                ?.get(CodigosBarraCacheTable.codprod)
            ?: return null

        val produto = ProdutosCacheTable.selectAll()
            .where { (ProdutosCacheTable.tenantId eq tenantId) and (ProdutosCacheTable.codprod eq codprod) }
            .singleOrNull() ?: return null

        val proximaSequencia = (SeparacaoItensTable.selectAll()
            .where { (SeparacaoItensTable.tenantId eq tenantId) and (SeparacaoItensTable.sessaoId eq sessaoId) }
            .maxOfOrNull { it[SeparacaoItensTable.sequencia] } ?: 0) + 1

        val dadosJson = buildJsonObject {
            put("Produto.DESCRPROD", produto[ProdutosCacheTable.descrprod])
            put("Produto.COMPLDESC", produto[ProdutosCacheTable.compldesc])
            put("Produto.MARCA", produto[ProdutosCacheTable.marca])
            put("Produto.REFERENCIA", produto[ProdutosCacheTable.referencia])
            put("Produto.TIPCONTEST", produto[ProdutosCacheTable.tipcontest])
            put("Produto.LISCONTEST", produto[ProdutosCacheTable.liscontest])
        }.toString()

        SeparacaoItensTable.insert {
            it[id] = UUID.randomUUID()
            it[SeparacaoItensTable.tenantId] = tenantId
            it[SeparacaoItensTable.sessaoId] = sessaoId
            it[sequencia] = proximaSequencia
            it[SeparacaoItensTable.codprod] = codprod
            it[controle] = " "
            it[codvol] = null
            it[qtdNeg] = BigDecimal.ZERO
            it[qtdEntregue] = BigDecimal.ZERO
            it[qtdConferidaLocal] = BigDecimal.ZERO
            it[usaConfPeso] = false
            it[foraPedido] = true
            it[SeparacaoItensTable.tipoSeparacao] = tipoSeparacao
            it[dados] = dadosJson
        }

        return ItemLocalResolucao(
            codprod = codprod,
            controle = " ",
            codvol = null,
            descricao = produto[ProdutosCacheTable.descrprod],
            referencia = produto[ProdutosCacheTable.referencia],
            tipControle = produto[ProdutosCacheTable.tipcontest],
            lisControles = produto[ProdutosCacheTable.liscontest],
            usaConfPeso = false,
        )
    }

    /** Só resolve, não confirma nada — usado por quem só quer saber "o que é esse código" sem bipar de verdade. */
    fun resolverCodigoBarras(tenantId: UUID, sessaoId: UUID, codigoBarraLido: String): ItemResolvido? = TenantTx.run(tenantId) {
        val sessao = SeparacaoSessoesTable.selectAll()
            .where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }
            .singleOrNull() ?: return@run null
        val regra = sessao[SeparacaoSessoesTable.buscarCodigoBarraPor]
        val itens = carregarItensLocais(tenantId, sessaoId)
        val codigos = carregarCodigosLocais(tenantId, sessaoId)
        resolverContra(itens, codigos, regra, codigoBarraLido)
    }

    private data class ItemLocalResolucao(
        val codprod: Int,
        val controle: String,
        val codvol: String?,
        val descricao: String?,
        val referencia: String?,
        val tipControle: String? = null,
        val lisControles: String? = null,
        val usaConfPeso: Boolean = false,
    )

    /**
     * Identifica o produto a partir do código bipado (mesma resolução de
     * sempre) e já devolve como o campo de controle deve se comportar na
     * tela — mesma regra do projeto base (`prepararSelecaoItem`):
     *
     * - `TIPCONTEST == 'L'` (Lote): campo vira digitação livre, label "Nº do
     *   Lote", sem lista de opções.
     * - Caso contrário (Lista): campo vira <select>. Opções vêm de
     *   `LISCONTEST` (lista pré-cadastrada, uma por linha) quando existe;
     *   senão, do conjunto de controles distintos entre as linhas desta nota
     *   pra esse produto (fallback do projeto base). Ausência de controle
     *   real vira o sentinel "SEM_CONTROLE" (mesmo texto/contrato do
     *   projeto base, pro front desenhar "Sem controle" e desabilitar).
     */
    fun identificarProduto(
        tenantId: UUID,
        sessaoId: UUID,
        codigoBarraLido: String,
        codprodDireto: Int? = null,
        /** Etapa ativa na tela (conferência segmentada) — item fora do pedido nasce nesta etapa. */
        tipoSeparacaoEtapa: Short? = null,
    ): IdentificarProdutoResultado? =
        TenantTx.run(tenantId) {
            val sessao = SeparacaoSessoesTable.selectAll()
                .where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }
                .singleOrNull() ?: return@run null
            val regra = sessao[SeparacaoSessoesTable.buscarCodigoBarraPor]

            var itens = carregarItensLocais(tenantId, sessaoId)
            val codigos = carregarCodigosLocais(tenantId, sessaoId)
            // Clique na lista de pendentes: resolve direto por CODPROD, na unidade
            // padrão (sem fator/VOA — o operador não escaneou uma unidade alternativa).
            var resolvido = if (codprodDireto != null) {
                itens.firstOrNull { it.codprod == codprodDireto }?.let {
                    ItemResolvido(
                        codprod = it.codprod,
                        descricaoProduto = it.descricao,
                        referencia = it.referencia,
                        codvol = it.codvol,
                        controle = " ",
                        fatorConversao = null,
                        divideMultiplica = null,
                    )
                }
            } else {
                resolverContra(itens, codigos, regra, codigoBarraLido)
            }

            // Produto não constante no pedido — PRODUTOSFORAPED da CCO ('D' =
            // permitido, mesma convenção já confirmada em QTDAMAIOR). Inclui
            // dinamicamente como item novo (qtd_neg=0 — nada foi negociado,
            // fica divergente por definição, é isso que a CCO decide aceitar).
            if (resolvido == null && sessao[SeparacaoSessoesTable.produtosForaPed] == "D") {
                val novoItem = incluirProdutoForaPedido(tenantId, sessaoId, codigoBarraLido, tipoSeparacaoEtapa ?: 1)
                if (novoItem != null) {
                    itens = itens + novoItem
                    resolvido = ItemResolvido(
                        codprod = novoItem.codprod,
                        descricaoProduto = novoItem.descricao,
                        referencia = novoItem.referencia,
                        codvol = novoItem.codvol,
                        controle = " ",
                        fatorConversao = null,
                        divideMultiplica = null,
                    )
                }
            }
            val resolvidoFinal = resolvido ?: return@run null

            val itensDoProduto = itens.filter { it.codprod == resolvidoFinal.codprod }
            val tipControle = itensDoProduto.firstOrNull()?.tipControle

            val usaConfPeso = itensDoProduto.any { it.usaConfPeso }

            // Unidade escanada (VOA) — vai junto no /conferir p/ virar CODVOL no Sankhya.
            val codvolEscanado = resolvidoFinal.codvol

            if (tipControle == "L") {
                return@run IdentificarProdutoResultado(
                    codprod = resolvidoFinal.codprod,
                    descricaoProduto = resolvidoFinal.descricaoProduto,
                    controleModoLote = true,
                    controlesDisponiveis = emptyList(),
                    controleAutoSelecionado = null,
                    controleTravado = false,
                    usaConfPeso = usaConfPeso,
                    codvol = codvolEscanado,
                )
            }

            val lisControles = itensDoProduto.firstOrNull()?.lisControles?.trim()
            val controlesDisponiveis = if (!lisControles.isNullOrEmpty()) {
                lisControles.split(Regex("\r?\n")).map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                itensDoProduto.map { it.controle.trim().ifEmpty { "SEM_CONTROLE" } }.distinct()
            }

            // Nada de "adivinhar" o controle: só duas situações travam/preenchem
            // o campo sozinhas — (1) o produto não tem controle nenhum, ou
            // (2) o código bipado veio de ESTOQUE (EST) e o registro de estoque
            // já tinha um controle associado. Fora isso (VOA, BAR, referência),
            // resolverContra devolve controle em branco de propósito — o
            // operador escolhe manualmente, sem sugestão "inteligente".
            val semControle = controlesDisponiveis.size == 1 && controlesDisponiveis[0] == "SEM_CONTROLE"
            val controleVeioDoEstoque = resolvidoFinal.controle.trim().isNotEmpty()
            val controleAutoSelecionado = when {
                semControle -> ""
                controleVeioDoEstoque && controlesDisponiveis.contains(resolvidoFinal.controle.trim()) -> resolvidoFinal.controle.trim()
                else -> null
            }
            val controleTravado = semControle || controleVeioDoEstoque

            IdentificarProdutoResultado(
                codprod = resolvidoFinal.codprod,
                descricaoProduto = resolvidoFinal.descricaoProduto,
                controleModoLote = false,
                controlesDisponiveis = controlesDisponiveis,
                controleAutoSelecionado = controleAutoSelecionado,
                controleTravado = controleTravado,
                usaConfPeso = usaConfPeso,
                codvol = codvolEscanado,
            )
        }

    /** Lançada quando a bipagem excederia a quantidade negociada e a CCO do NUCCO não permite (QTDAMAIOR != 'D'). */
    class QuantidadeExcedeException(val maximo: BigDecimal) : Exception("quantidade excede o pendente (máximo $maximo)")

    /**
     * Confirma a quantidade de um item JÁ IDENTIFICADO (produto+controle
     * explícitos, vindos do passo de identificação — não re-resolve código
     * de barras aqui, então é mais rápido e não ambíguo). Grava a leitura
     * (auditoria) e recalcula `qtd_conferida_local` a partir da soma —
     * mesma ideia do `registrarLeitura`/`recalcularQtdItem` do projeto base.
     *
     * [permitirQtdMaior] vem da CCO do NUCCO (QTDAMAIOR='D') — quando false,
     * bloqueia ANTES de gravar a leitura (não só marca divergente depois),
     * mesma UX do sistema nativo Sankhya documentada oficialmente.
     */
    fun conferirItem(
        tenantId: UUID,
        sessaoId: UUID,
        codprod: Int,
        controleInformado: String,
        qtd: BigDecimal,
        permitirQtdMaior: Boolean,
        peso: BigDecimal? = null,
        codvolEscanado: String? = null,
        codigoBarra: String? = null,
    ): ItemConferidoResultado? =
        TenantTx.run(tenantId) {
            val controle = controleInformado.trim().ifEmpty { " " }

            val itensDoGrupo = SeparacaoItensTable.selectAll()
                .where {
                    (SeparacaoItensTable.tenantId eq tenantId) and
                        (SeparacaoItensTable.sessaoId eq sessaoId) and
                        (SeparacaoItensTable.codprod eq codprod) and
                        (SeparacaoItensTable.controle eq controle)
                }
                .toList()
            if (itensDoGrupo.isEmpty()) return@run null

            // Fora do pedido é divergente por definição (qtd_neg=0 — nada foi
            // negociado) — o teto de QTDAMAIOR não se aplica aqui, quem já
            // decidiu aceitar essa divergência foi a CCO (PRODUTOSFORAPED),
            // lá no momento de identificar o produto.
            val ehForaPedido = itensDoGrupo.any { it[SeparacaoItensTable.foraPedido] }

            if (!permitirQtdMaior && !ehForaPedido) {
                val totalNegociado = itensDoGrupo.fold(BigDecimal.ZERO) { acc, row -> acc + row[SeparacaoItensTable.qtdNeg] }
                val jaLido = SeparacaoLeiturasTable.selectAll()
                    .where {
                        (SeparacaoLeiturasTable.tenantId eq tenantId) and
                            (SeparacaoLeiturasTable.sessaoId eq sessaoId) and
                            (SeparacaoLeiturasTable.codprod eq codprod) and
                            (SeparacaoLeiturasTable.controle eq controle)
                    }
                    .sumOf { it[SeparacaoLeiturasTable.qtd] }
                if (jaLido + qtd > totalNegociado) {
                    throw QuantidadeExcedeException((totalNegociado - jaLido).max(BigDecimal.ZERO))
                }
            }

            val agora = Instant.now()
            SeparacaoLeiturasTable.insert {
                it[id] = UUID.randomUUID()
                it[SeparacaoLeiturasTable.tenantId] = tenantId
                it[SeparacaoLeiturasTable.sessaoId] = sessaoId
                it[SeparacaoLeiturasTable.codprod] = codprod
                it[SeparacaoLeiturasTable.controle] = controle
                it[SeparacaoLeiturasTable.codvol] = codvolEscanado?.trim()?.takeIf { c -> c.isNotEmpty() }
                it[SeparacaoLeiturasTable.codigoBarra] = codigoBarra?.trim()?.takeIf { c -> c.isNotEmpty() }
                it[SeparacaoLeiturasTable.qtd] = qtd
                it[SeparacaoLeiturasTable.peso] = peso
                it[criadoEm] = agora
            }

            val totalLido = SeparacaoLeiturasTable.selectAll()
                .where {
                    (SeparacaoLeiturasTable.tenantId eq tenantId) and
                        (SeparacaoLeiturasTable.sessaoId eq sessaoId) and
                        (SeparacaoLeiturasTable.codprod eq codprod) and
                        (SeparacaoLeiturasTable.controle eq controle)
                }
                .sumOf { it[SeparacaoLeiturasTable.qtd] }

            val linhasAlvo = SeparacaoItensTable.selectAll()
                .where {
                    (SeparacaoItensTable.tenantId eq tenantId) and
                        (SeparacaoItensTable.sessaoId eq sessaoId) and
                        (SeparacaoItensTable.codprod eq codprod) and
                        (SeparacaoItensTable.controle eq controle)
                }
                .orderBy(SeparacaoItensTable.sequencia to SortOrder.ASC)
                .toList()

            if (linhasAlvo.isEmpty()) return@run null

            var restante = totalLido
            var ultimaSequencia = linhasAlvo.first()[SeparacaoItensTable.sequencia]
            var ultimaQtdConferida = BigDecimal.ZERO
            var descricaoProduto: String? = null
            for ((idx, linha) in linhasAlvo.withIndex()) {
                val ehUltima = idx == linhasAlvo.lastIndex
                val qtdNegLinha = linha[SeparacaoItensTable.qtdNeg]
                val alocado = if (ehUltima) restante else restante.min(qtdNegLinha)
                SeparacaoItensTable.update({ SeparacaoItensTable.id eq linha[SeparacaoItensTable.id] }) {
                    it[qtdConferidaLocal] = alocado.max(BigDecimal.ZERO)
                }
                restante = (restante - alocado).max(BigDecimal.ZERO)
                ultimaSequencia = linha[SeparacaoItensTable.sequencia]
                ultimaQtdConferida = alocado.max(BigDecimal.ZERO)
                if (ehUltima) {
                    val dados = runCatching { Json.parseToJsonElement(linha[SeparacaoItensTable.dados]) as JsonObject }.getOrNull()
                    descricaoProduto = dados?.get("Produto.DESCRPROD")?.jsonPrimitive?.contentOrNull
                }
            }

            ItemConferidoResultado(
                sequencia = ultimaSequencia,
                codprod = codprod,
                controle = controle,
                descricaoProduto = descricaoProduto,
                qtdConferidaLocal = ultimaQtdConferida.toPlainString(),
                qtdTotalLida = totalLido.toPlainString(),
            )
        }

    /**
     * Devolve (desfaz) TUDO que foi conferido pra esse produto+controle
     * nesta sessão — mesma semântica do `devolverItem` do projeto base: não
     * é "desfazer o último bipe", é "zerar esse item, ele volta a pendente
     * do zero". Apaga as leituras (não fica lixo de auditoria de algo que
     * nunca deveria ter contado) e zera `qtd_conferida_local`.
     */
    fun devolverItem(tenantId: UUID, sessaoId: UUID, codprod: Int, controleInformado: String): Boolean = TenantTx.run(tenantId) {
        val controle = controleInformado.trim().ifEmpty { " " }

        val linhas = SeparacaoItensTable.selectAll()
            .where {
                (SeparacaoItensTable.tenantId eq tenantId) and
                    (SeparacaoItensTable.sessaoId eq sessaoId) and
                    (SeparacaoItensTable.codprod eq codprod) and
                    (SeparacaoItensTable.controle eq controle)
            }
            .toList()
        if (linhas.isEmpty()) return@run false

        SeparacaoLeiturasTable.deleteWhere {
            (SeparacaoLeiturasTable.tenantId eq tenantId) and
                (SeparacaoLeiturasTable.sessaoId eq sessaoId) and
                (SeparacaoLeiturasTable.codprod eq codprod) and
                (SeparacaoLeiturasTable.controle eq controle)
        }

        // Produto FORA DO PEDIDO só existe porque foi bipado — desfazer a
        // conferência dele = apagar a linha, não volta pra "pendentes" (nunca
        // foi pendente; a lista de pendentes é o pedido negociado).
        val ehForaPedido = linhas.all { it[SeparacaoItensTable.foraPedido] }
        if (ehForaPedido) {
            SeparacaoItensTable.deleteWhere {
                (SeparacaoItensTable.tenantId eq tenantId) and
                    (SeparacaoItensTable.sessaoId eq sessaoId) and
                    (SeparacaoItensTable.codprod eq codprod) and
                    (SeparacaoItensTable.controle eq controle)
            }
        } else {
            SeparacaoItensTable.update({
                (SeparacaoItensTable.tenantId eq tenantId) and
                    (SeparacaoItensTable.sessaoId eq sessaoId) and
                    (SeparacaoItensTable.codprod eq codprod) and
                    (SeparacaoItensTable.controle eq controle)
            }) {
                it[qtdConferidaLocal] = BigDecimal.ZERO
            }
        }

        true
    }

    fun buscarSessao(tenantId: UUID, sessaoId: UUID): SessaoSeparacaoDto? = TenantTx.run(tenantId) {
        SeparacaoSessoesTable.selectAll()
            .where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }
            .singleOrNull()
            ?.let {
                SessaoSeparacaoDto(
                    id = it[SeparacaoSessoesTable.id].toString(),
                    nunota = it[SeparacaoSessoesTable.nunota].toLong(),
                    status = it[SeparacaoSessoesTable.status],
                    erro = it[SeparacaoSessoesTable.erro],
                    obterQtdBalanca = it[SeparacaoSessoesTable.obterQtdBalanca],
                    conferenciaSegmentada = it[SeparacaoSessoesTable.conferenciaSegmentada],
                    fatAoConcluir = it[SeparacaoSessoesTable.fatAoConcluir],
                    exibirProd = it[SeparacaoSessoesTable.exibirProd],
                    exibirQtd = it[SeparacaoSessoesTable.exibirQtd],
                    exibirProdConf = it[SeparacaoSessoesTable.exibirProdConf],
                    exibirQtdConf = it[SeparacaoSessoesTable.exibirQtdConf],
                    exibirImgProd = it[SeparacaoSessoesTable.exibirImgProd],
                )
            }
    }

    /**
     * Conferências que o WMS finalizou (separacao_sessoes.status='concluida'),
     * enriquecidas com os dados da tarefa (parceiro/tipo op/data). Local, sem
     * Sankhya — pra tela de reimpressão de etiquetas.
     */
    fun listarConferenciasFinalizadas(
        tenantId: UUID,
        nunota: Long?,
        numnota: Long?,
        page: Int,
        perPage: Int,
    ): ConferenciasFinalizadasResponse = TenantTx.run(tenantId) {
        val base = SeparacaoSessoesTable.selectAll()
            .where {
                var cond = (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.status eq SeparacaoStatus.CONCLUIDA)
                if (nunota != null) cond = cond and (SeparacaoSessoesTable.nunota eq nunota.toInt())
                cond
            }
            .orderBy(SeparacaoSessoesTable.criadoEm to SortOrder.DESC)
            .toList()

        val nunotas = base.map { it[SeparacaoSessoesTable.nunota] }.distinct()
        val dadosPorNunota = if (nunotas.isEmpty()) {
            emptyMap()
        } else {
            TarefasTable.selectAll()
                .where { (TarefasTable.tenantId eq tenantId) and (TarefasTable.nunota inList nunotas) }
                .associate { row ->
                    val d = runCatching { Json.parseToJsonElement(row[TarefasTable.dados]) as JsonObject }.getOrNull()
                    row[TarefasTable.nunota] to d
                }
        }

        val todos = base.mapNotNull { row ->
            val nn = row[SeparacaoSessoesTable.nunota]
            val d = dadosPorNunota[nn]
            val numeroNota = d?.get("NUMNOTA")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            if (numnota != null && numeroNota != numnota) return@mapNotNull null
            ConferenciaFinalizadaDto(
                sessaoId = row[SeparacaoSessoesTable.id].toString(),
                nunota = nn.toLong(),
                numeroNota = numeroNota,
                nomeParceiro = d?.get("Parceiro.NOMEPARC")?.jsonPrimitive?.contentOrNull,
                descricaoTipoOperacao = d?.get("TipoOperacao.DESCROPER")?.jsonPrimitive?.contentOrNull,
                dataMovimento = d?.get("DTNEG")?.jsonPrimitive?.contentOrNull,
                apelidoVendedor = d?.get("Vendedor.APELIDO")?.jsonPrimitive?.contentOrNull,
                nuconf = row[SeparacaoSessoesTable.nuconf],
            )
        }

        val total = todos.size
        val pagina = todos.drop(page * perPage).take(perPage)
        ConferenciasFinalizadasResponse(itens = pagina, total = total, page = page, perPage = perPage)
    }

    /** Sessão mais recente (por criado_em) de uma nota — usado pra reimpressão de etiqueta fora da tela de conferência. */
    fun buscarSessaoMaisRecentePorNota(tenantId: UUID, nunota: Long): SessaoSeparacaoDto? = TenantTx.run(tenantId) {
        SeparacaoSessoesTable.selectAll()
            .where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.nunota eq nunota.toInt()) }
            .orderBy(SeparacaoSessoesTable.criadoEm to SortOrder.DESC)
            .firstOrNull()
            ?.let { mapearParaDto(it) }
    }

    /** NUNOTA da sessão que tem este NUCONF (mais recente). Usado pela liberação de corte pra fechar a tarefa local. */
    fun buscarNunotaPorNuconf(tenantId: UUID, nuconf: Int): Long? = TenantTx.run(tenantId) {
        SeparacaoSessoesTable.selectAll()
            .where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.nuconf eq nuconf) }
            .orderBy(SeparacaoSessoesTable.criadoEm to SortOrder.DESC)
            .firstOrNull()
            ?.get(SeparacaoSessoesTable.nunota)
            ?.toLong()
    }

    /** NUCONF da conferência mais recente de uma nota (qualquer status de sessão). */
    fun buscarNuconfPorNota(tenantId: UUID, nunota: Long): Int? = TenantTx.run(tenantId) {
        SeparacaoSessoesTable.selectAll()
            .where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.nunota eq nunota.toInt()) }
            .orderBy(SeparacaoSessoesTable.criadoEm to SortOrder.DESC)
            .firstOrNull()
            ?.get(SeparacaoSessoesTable.nuconf)
    }

    fun listarItens(tenantId: UUID, sessaoId: UUID): List<ItemSeparacaoDto> = TenantTx.run(tenantId) {
        SeparacaoItensTable.selectAll()
            .where { (SeparacaoItensTable.tenantId eq tenantId) and (SeparacaoItensTable.sessaoId eq sessaoId) }
            .orderBy(SeparacaoItensTable.sequencia to SortOrder.ASC)
            .map { row ->
                val dados = runCatching { Json.parseToJsonElement(row[SeparacaoItensTable.dados]) as JsonObject }.getOrNull()
                val qtdNeg = row[SeparacaoItensTable.qtdNeg]
                val qtdConf = row[SeparacaoItensTable.qtdConferidaLocal]
                val divideMult = row[SeparacaoItensTable.divideMultiplica]
                val fator = row[SeparacaoItensTable.fatorConversao]
                val unidadePadrao = row[SeparacaoItensTable.unidadePadrao] ?: row[SeparacaoItensTable.codvol]
                val unidadeComercial = row[SeparacaoItensTable.unidadeComercial] ?: unidadePadrao
                ItemSeparacaoDto(
                    sequencia = row[SeparacaoItensTable.sequencia],
                    codprod = row[SeparacaoItensTable.codprod],
                    controle = row[SeparacaoItensTable.controle],
                    codvol = row[SeparacaoItensTable.codvol],
                    qtdNeg = qtdNeg.toPlainString(),
                    qtdEntregue = row[SeparacaoItensTable.qtdEntregue].toPlainString(),
                    qtdConferidaLocal = qtdConf.toPlainString(),
                    descricaoProduto = dados?.get("Produto.DESCRPROD")?.jsonPrimitive?.contentOrNull,
                    complementoDescricao = dados?.get("Produto.COMPLDESC")?.jsonPrimitive?.contentOrNull,
                    marca = dados?.get("Produto.MARCA")?.jsonPrimitive?.contentOrNull,
                    referencia = dados?.get("Produto.REFERENCIA")?.jsonPrimitive?.contentOrNull,
                    usaConfPeso = row[SeparacaoItensTable.usaConfPeso],
                    foraPedido = row[SeparacaoItensTable.foraPedido],
                    tipoSeparacao = row[SeparacaoItensTable.tipoSeparacao].toInt(),
                    unidadeComercial = unidadeComercial,
                    unidadePadrao = unidadePadrao,
                    quantidadePadrao = qtdNeg.toPlainString(),
                    quantidadeComercial = padraoParaComercial(qtdNeg, divideMult, fator).toPlainString(),
                    quantidadePadraoConferida = qtdConf.toPlainString(),
                    quantidadeComercialConferida = padraoParaComercial(qtdConf, divideMult, fator).toPlainString(),
                )
            }
    }

    /**
     * Converte uma quantidade da unidade PADRÃO pra unidade COMERCIAL (VOA) —
     * espelha fila-conferencia sessao.service.ts:826-852. Só p/ display.
     * 'M' → comercial = padrão / fator ; 'D' → comercial = padrão * fator ; senão 1:1.
     */
    private fun padraoParaComercial(padrao: BigDecimal, divideMultiplica: String?, fator: BigDecimal?): BigDecimal {
        val f = fator ?: BigDecimal.ONE
        return when {
            divideMultiplica == "M" && f.signum() != 0 -> padrao.divide(f, 5, java.math.RoundingMode.HALF_UP)
            divideMultiplica == "D" -> (padrao * f).setScale(5, java.math.RoundingMode.HALF_UP)
            else -> padrao
        }
    }

    data class GrupoConferido(
        val codprod: Int,
        val controle: String,
        val qtdTotal: BigDecimal,
        /** Unidade escanada da última leitura do grupo (p/ CODVOL no Sankhya). */
        val codvol: String? = null,
        /** Código de barras escanado da última leitura do grupo (p/ CODBARRA no Sankhya). */
        val codigoBarra: String? = null,
    )

    /**
     * Agrupa por produto+controle (mesma regra do legado antes de escrever em
     * TGFCOI2) — uma nota pode ter o mesmo produto em mais de uma SEQUENCIA
     * (ex.: entregas parciais), e ConferenciaSP.salvarItemConferido é "grava
     * uma vez, valor final" por produto+controle, não por SEQUENCIA. Só
     * inclui grupos com quantidade conferida > 0 (nada bipado não entra na
     * conferência nativa).
     */
    fun listarGruposConferidos(tenantId: UUID, sessaoId: UUID): List<GrupoConferido> = TenantTx.run(tenantId) {
        // codvol / codigo_barra escanados por (codprod, controle) — pega a leitura
        // mais recente que tenha esses campos preenchidos.
        val leiturasDoGrupo = SeparacaoLeiturasTable.selectAll()
            .where { (SeparacaoLeiturasTable.tenantId eq tenantId) and (SeparacaoLeiturasTable.sessaoId eq sessaoId) }
            .orderBy(SeparacaoLeiturasTable.criadoEm to SortOrder.DESC)
            .toList()
        fun escaneadoPara(codprod: Int, controle: String): Pair<String?, String?> {
            val ls = leiturasDoGrupo.filter {
                it[SeparacaoLeiturasTable.codprod] == codprod && it[SeparacaoLeiturasTable.controle] == controle
            }
            val cv = ls.firstNotNullOfOrNull { it[SeparacaoLeiturasTable.codvol] }
            val cb = ls.firstNotNullOfOrNull { it[SeparacaoLeiturasTable.codigoBarra] }
            return cv to cb
        }

        SeparacaoItensTable.selectAll()
            .where { (SeparacaoItensTable.tenantId eq tenantId) and (SeparacaoItensTable.sessaoId eq sessaoId) }
            .groupBy { it[SeparacaoItensTable.codprod] to it[SeparacaoItensTable.controle] }
            .mapNotNull { (chave, linhas) ->
                val total = linhas.fold(BigDecimal.ZERO) { acc, row -> acc + row[SeparacaoItensTable.qtdConferidaLocal] }
                if (total <= BigDecimal.ZERO) {
                    null
                } else {
                    val (cv, cb) = escaneadoPara(chave.first, chave.second)
                    GrupoConferido(chave.first, chave.second, total, codvol = cv, codigoBarra = cb)
                }
            }
    }

    /**
     * Revalida a sessão contra o que o job de sync (TarefaSyncService) já
     * sabe sobre a tarefa — SEM chamar o Sankhya de novo. Se a tarefa não
     * existe mais (saiu do critério da fila) OU seu status_operacional virou
     * 'cancelado' (ConferenciaSP marcou STATUS='D' — ver StatusOperacional.kt
     * mapearStatusSankhya), a conferência foi excluída no Sankhya enquanto
     * esta sessão local ainda estava aberta: invalida na hora, preservando o
     * motivo, em vez de deixar o operador continuar separando itens que já
     * não existem mais lá.
     */
    fun revalidar(tenantId: UUID, sessaoId: UUID): SessaoSeparacaoDto? = TenantTx.run(tenantId) {
        val sessao = SeparacaoSessoesTable.selectAll()
            .where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }
            .singleOrNull() ?: return@run null

        val statusAtual = sessao[SeparacaoSessoesTable.status]
        if (statusAtual != SeparacaoStatus.CARREGANDO && statusAtual != SeparacaoStatus.PRONTA) {
            return@run mapearParaDto(sessao)
        }

        val tarefa = sessao[SeparacaoSessoesTable.tarefaId]?.let { tarefaId ->
            TarefasTable.selectAll().where { TarefasTable.id eq tarefaId }.singleOrNull()
        }

        val motivoInvalidacao = when {
            tarefa == null -> "Tarefa não encontrada mais na fila local — provável exclusão da conferência no Sankhya"
            tarefa[TarefasTable.statusOperacional] == "cancelado" -> "Conferência cancelada/excluída no Sankhya (detectado pelo sync)"
            else -> null
        }

        if (motivoInvalidacao == null) return@run mapearParaDto(sessao)

        SeparacaoSessoesTable.update({ (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }) {
            it[status] = SeparacaoStatus.INVALIDADA
            it[erro] = motivoInvalidacao
            it[atualizadoEm] = Instant.now()
        }
        SessaoSeparacaoDto(
            id = sessaoId.toString(),
            nunota = sessao[SeparacaoSessoesTable.nunota].toLong(),
            status = SeparacaoStatus.INVALIDADA,
            erro = motivoInvalidacao,
        )
    }

    private fun mapearParaDto(row: org.jetbrains.exposed.sql.ResultRow) = SessaoSeparacaoDto(
        id = row[SeparacaoSessoesTable.id].toString(),
        nunota = row[SeparacaoSessoesTable.nunota].toLong(),
        status = row[SeparacaoSessoesTable.status],
        erro = row[SeparacaoSessoesTable.erro],
        obterQtdBalanca = row[SeparacaoSessoesTable.obterQtdBalanca],
        conferenciaSegmentada = row[SeparacaoSessoesTable.conferenciaSegmentada],
        fatAoConcluir = row[SeparacaoSessoesTable.fatAoConcluir],
        exibirProd = row[SeparacaoSessoesTable.exibirProd],
        exibirQtd = row[SeparacaoSessoesTable.exibirQtd],
        exibirProdConf = row[SeparacaoSessoesTable.exibirProdConf],
        exibirQtdConf = row[SeparacaoSessoesTable.exibirQtdConf],
        exibirImgProd = row[SeparacaoSessoesTable.exibirImgProd],
    )

    // ─── Conferência por etapa (V29) ─────────────────────────────────────────

    /** Cria uma etapa 'P' por tipo de separação presente na nota. Idempotente. */
    fun semearEtapas(tenantId: UUID, sessaoId: UUID, tiposPresentes: Set<Short>): Unit = TenantTx.run(tenantId) {
        if (tiposPresentes.isEmpty()) return@run
        val jaExistem = SeparacaoEtapasTable.selectAll()
            .where { (SeparacaoEtapasTable.tenantId eq tenantId) and (SeparacaoEtapasTable.sessaoId eq sessaoId) }
            .map { it[SeparacaoEtapasTable.tipoSeparacao] }
            .toSet()
        val faltando = tiposPresentes - jaExistem
        if (faltando.isEmpty()) return@run
        SeparacaoEtapasTable.batchInsert(faltando) { tipo ->
            this[SeparacaoEtapasTable.id] = UUID.randomUUID()
            this[SeparacaoEtapasTable.tenantId] = tenantId
            this[SeparacaoEtapasTable.sessaoId] = sessaoId
            this[SeparacaoEtapasTable.tipoSeparacao] = tipo
            this[SeparacaoEtapasTable.status] = SeparacaoEtapaStatus.PENDENTE
            this[SeparacaoEtapasTable.criadoEm] = Instant.now()
        }
        Unit
    }

    fun listarEtapas(tenantId: UUID, sessaoId: UUID): List<EtapaSeparacaoDto> = TenantTx.run(tenantId) {
        SeparacaoEtapasTable.selectAll()
            .where { (SeparacaoEtapasTable.tenantId eq tenantId) and (SeparacaoEtapasTable.sessaoId eq sessaoId) }
            .orderBy(SeparacaoEtapasTable.tipoSeparacao to SortOrder.ASC)
            .map {
                EtapaSeparacaoDto(
                    tipoSeparacao = it[SeparacaoEtapasTable.tipoSeparacao].toInt(),
                    status = it[SeparacaoEtapasTable.status],
                    concluidaPor = it[SeparacaoEtapasTable.concluidaPor],
                    concluidaEm = it[SeparacaoEtapasTable.concluidaEm]?.toString(),
                )
            }
    }

    /** Itens ainda não conferidos (qtd conferida < negociada) de uma etapa — ignora fora-do-pedido. */
    fun contarPendentesDaEtapa(tenantId: UUID, sessaoId: UUID, tipoSeparacao: Short): Int = TenantTx.run(tenantId) {
        SeparacaoItensTable.selectAll()
            .where {
                (SeparacaoItensTable.tenantId eq tenantId) and
                    (SeparacaoItensTable.sessaoId eq sessaoId) and
                    (SeparacaoItensTable.tipoSeparacao eq tipoSeparacao) and
                    (SeparacaoItensTable.foraPedido eq false)
            }
            .count { it[SeparacaoItensTable.qtdConferidaLocal] < it[SeparacaoItensTable.qtdNeg] }
    }

    /** Marca a etapa concluída. Retorna false se a etapa não existe ou já estava 'C'. */
    fun concluirEtapa(tenantId: UUID, sessaoId: UUID, tipoSeparacao: Short, operador: String): Boolean = TenantTx.run(tenantId) {
        val n = SeparacaoEtapasTable.update({
            (SeparacaoEtapasTable.tenantId eq tenantId) and
                (SeparacaoEtapasTable.sessaoId eq sessaoId) and
                (SeparacaoEtapasTable.tipoSeparacao eq tipoSeparacao) and
                (SeparacaoEtapasTable.status eq SeparacaoEtapaStatus.PENDENTE)
        }) {
            it[status] = SeparacaoEtapaStatus.CONCLUIDA
            it[concluidaPor] = operador
            it[concluidaEm] = Instant.now()
        }
        n > 0
    }

    /** true = a sessão tem etapas e TODAS estão 'C'. false = não tem etapas, ou alguma pendente. */
    fun todasEtapasConcluidas(tenantId: UUID, sessaoId: UUID): Boolean = TenantTx.run(tenantId) {
        val etapas = SeparacaoEtapasTable.selectAll()
            .where { (SeparacaoEtapasTable.tenantId eq tenantId) and (SeparacaoEtapasTable.sessaoId eq sessaoId) }
            .map { it[SeparacaoEtapasTable.status] }
        etapas.isNotEmpty() && etapas.all { it == SeparacaoEtapaStatus.CONCLUIDA }
    }

    /** Tipos de separação já concluídos, por nunota — pro card da fila. */
    fun etapasConcluidasPorNunota(tenantId: UUID, nunotas: List<Long>): Map<Long, List<Int>> = TenantTx.run(tenantId) {
        if (nunotas.isEmpty()) return@run emptyMap()
        val nunotasInt = nunotas.map { it.toInt() }
        // sessao_id -> nunota (todas as sessões dessas notas)
        val nunotaPorSessao = SeparacaoSessoesTable.selectAll()
            .where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.nunota inList nunotasInt) }
            .associate { it[SeparacaoSessoesTable.id] to it[SeparacaoSessoesTable.nunota].toLong() }
        if (nunotaPorSessao.isEmpty()) return@run emptyMap()
        SeparacaoEtapasTable.selectAll()
            .where {
                (SeparacaoEtapasTable.tenantId eq tenantId) and
                    (SeparacaoEtapasTable.status eq SeparacaoEtapaStatus.CONCLUIDA) and
                    (SeparacaoEtapasTable.sessaoId inList nunotaPorSessao.keys)
            }
            .mapNotNull { row ->
                val nunota = nunotaPorSessao[row[SeparacaoEtapasTable.sessaoId]] ?: return@mapNotNull null
                nunota to row[SeparacaoEtapasTable.tipoSeparacao].toInt()
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, v) -> v.distinct().sorted() }
    }

    data class ProgressoEtapa(val total: Int, val conferidos: Int)

    /**
     * Progresso (itens / conferidos) por nunota e tipo de separação, lendo os
     * itens da sessão ATIVA de cada nota — pro "Continuar 3/8" no card.
     * Retomar a conferência de outro device já funciona (sessão persiste, nada
     * é enviado ao Sankhya até "Concluir Etapa"); isto só torna o progresso visível.
     */
    fun progressoEtapasPorNunota(tenantId: UUID, nunotas: List<Long>): Map<Long, Map<Int, ProgressoEtapa>> = TenantTx.run(tenantId) {
        if (nunotas.isEmpty()) return@run emptyMap()
        val nunotasInt = nunotas.map { it.toInt() }
        val sessaoPorNunota = SeparacaoSessoesTable.selectAll()
            .where {
                (SeparacaoSessoesTable.tenantId eq tenantId) and
                    (SeparacaoSessoesTable.nunota inList nunotasInt) and
                    (SeparacaoSessoesTable.status inList listOf(SeparacaoStatus.CARREGANDO, SeparacaoStatus.PRONTA))
            }
            .associate { it[SeparacaoSessoesTable.id] to it[SeparacaoSessoesTable.nunota].toLong() }
        if (sessaoPorNunota.isEmpty()) return@run emptyMap()

        val acc = HashMap<Long, HashMap<Int, IntArray>>() // nunota -> tipo -> [total, conferidos]
        SeparacaoItensTable.selectAll()
            .where {
                (SeparacaoItensTable.tenantId eq tenantId) and
                    (SeparacaoItensTable.sessaoId inList sessaoPorNunota.keys) and
                    (SeparacaoItensTable.foraPedido eq false)
            }
            .forEach { row ->
                val nunota = sessaoPorNunota[row[SeparacaoItensTable.sessaoId]] ?: return@forEach
                val tipo = row[SeparacaoItensTable.tipoSeparacao].toInt()
                val par = acc.getOrPut(nunota) { HashMap() }.getOrPut(tipo) { intArrayOf(0, 0) }
                par[0]++
                if (row[SeparacaoItensTable.qtdConferidaLocal] >= row[SeparacaoItensTable.qtdNeg]) par[1]++
            }
        acc.mapValues { (_, porTipo) -> porTipo.mapValues { (_, p) -> ProgressoEtapa(p[0], p[1]) } }
    }
}
