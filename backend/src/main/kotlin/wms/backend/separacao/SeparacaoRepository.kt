package wms.backend.separacao

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
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

    fun marcarPronta(tenantId: UUID, sessaoId: UUID, fingerprint: String, buscarCodigoBarraPor: String): Unit = TenantTx.run(tenantId) {
        SeparacaoSessoesTable.update({ (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }) {
            it[status] = SeparacaoStatus.PRONTA
            it[fingerprintItens] = fingerprint
            it[SeparacaoSessoesTable.buscarCodigoBarraPor] = buscarCodigoBarraPor
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
            this[SeparacaoItensTable.dados] = item.dadosJson
        }
        Unit
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
    fun identificarProduto(tenantId: UUID, sessaoId: UUID, codigoBarraLido: String): IdentificarProdutoResultado? =
        TenantTx.run(tenantId) {
            val sessao = SeparacaoSessoesTable.selectAll()
                .where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.id eq sessaoId) }
                .singleOrNull() ?: return@run null
            val regra = sessao[SeparacaoSessoesTable.buscarCodigoBarraPor]

            val itens = carregarItensLocais(tenantId, sessaoId)
            val codigos = carregarCodigosLocais(tenantId, sessaoId)
            val resolvido = resolverContra(itens, codigos, regra, codigoBarraLido) ?: return@run null

            val itensDoProduto = itens.filter { it.codprod == resolvido.codprod }
            val tipControle = itensDoProduto.firstOrNull()?.tipControle

            if (tipControle == "L") {
                return@run IdentificarProdutoResultado(
                    codprod = resolvido.codprod,
                    descricaoProduto = resolvido.descricaoProduto,
                    controleModoLote = true,
                    controlesDisponiveis = emptyList(),
                    controleAutoSelecionado = null,
                    controleTravado = false,
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
            val controleVeioDoEstoque = resolvido.controle.trim().isNotEmpty()
            val controleAutoSelecionado = when {
                semControle -> ""
                controleVeioDoEstoque && controlesDisponiveis.contains(resolvido.controle.trim()) -> resolvido.controle.trim()
                else -> null
            }
            val controleTravado = semControle || controleVeioDoEstoque

            IdentificarProdutoResultado(
                codprod = resolvido.codprod,
                descricaoProduto = resolvido.descricaoProduto,
                controleModoLote = false,
                controlesDisponiveis = controlesDisponiveis,
                controleAutoSelecionado = controleAutoSelecionado,
                controleTravado = controleTravado,
            )
        }

    /**
     * Confirma a quantidade de um item JÁ IDENTIFICADO (produto+controle
     * explícitos, vindos do passo de identificação — não re-resolve código
     * de barras aqui, então é mais rápido e não ambíguo). Grava a leitura
     * (auditoria) e recalcula `qtd_conferida_local` a partir da soma —
     * mesma ideia do `registrarLeitura`/`recalcularQtdItem` do projeto base.
     */
    fun conferirItem(tenantId: UUID, sessaoId: UUID, codprod: Int, controleInformado: String, qtd: BigDecimal): ItemConferidoResultado? =
        TenantTx.run(tenantId) {
            val controle = controleInformado.trim().ifEmpty { " " }

            val existeItem = SeparacaoItensTable.selectAll()
                .where {
                    (SeparacaoItensTable.tenantId eq tenantId) and
                        (SeparacaoItensTable.sessaoId eq sessaoId) and
                        (SeparacaoItensTable.codprod eq codprod)
                }
                .any()
            if (!existeItem) return@run null

            val agora = Instant.now()
            SeparacaoLeiturasTable.insert {
                it[id] = UUID.randomUUID()
                it[SeparacaoLeiturasTable.tenantId] = tenantId
                it[SeparacaoLeiturasTable.sessaoId] = sessaoId
                it[SeparacaoLeiturasTable.codprod] = codprod
                it[SeparacaoLeiturasTable.controle] = controle
                it[codvol] = null
                it[SeparacaoLeiturasTable.qtd] = qtd
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

        SeparacaoItensTable.update({
            (SeparacaoItensTable.tenantId eq tenantId) and
                (SeparacaoItensTable.sessaoId eq sessaoId) and
                (SeparacaoItensTable.codprod eq codprod) and
                (SeparacaoItensTable.controle eq controle)
        }) {
            it[qtdConferidaLocal] = BigDecimal.ZERO
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
                )
            }
    }

    fun listarItens(tenantId: UUID, sessaoId: UUID): List<ItemSeparacaoDto> = TenantTx.run(tenantId) {
        SeparacaoItensTable.selectAll()
            .where { (SeparacaoItensTable.tenantId eq tenantId) and (SeparacaoItensTable.sessaoId eq sessaoId) }
            .orderBy(SeparacaoItensTable.sequencia to SortOrder.ASC)
            .map { row ->
                val dados = runCatching { Json.parseToJsonElement(row[SeparacaoItensTable.dados]) as JsonObject }.getOrNull()
                ItemSeparacaoDto(
                    sequencia = row[SeparacaoItensTable.sequencia],
                    codprod = row[SeparacaoItensTable.codprod],
                    controle = row[SeparacaoItensTable.controle],
                    codvol = row[SeparacaoItensTable.codvol],
                    qtdNeg = row[SeparacaoItensTable.qtdNeg].toPlainString(),
                    qtdEntregue = row[SeparacaoItensTable.qtdEntregue].toPlainString(),
                    qtdConferidaLocal = row[SeparacaoItensTable.qtdConferidaLocal].toPlainString(),
                    descricaoProduto = dados?.get("Produto.DESCRPROD")?.jsonPrimitive?.contentOrNull,
                    complementoDescricao = dados?.get("Produto.COMPLDESC")?.jsonPrimitive?.contentOrNull,
                    marca = dados?.get("Produto.MARCA")?.jsonPrimitive?.contentOrNull,
                    referencia = dados?.get("Produto.REFERENCIA")?.jsonPrimitive?.contentOrNull,
                )
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
    )
}
