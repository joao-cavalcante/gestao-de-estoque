package wms.backend.mapaseparacao

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import wms.backend.erp.LoadRecordsRequest
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.erp.SankhyaLoadRecordsClient
import wms.backend.tarefas.StatusOperacional
import wms.backend.tarefas.TarefasRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Porte do Dashboard HTML5 "Separação de Ordem de Carga" (JSP no Sankhya,
 * ver 189_html5Component.zip) pro WMS — mesma regra de negócio, mesmas
 * tabelas, servindo o relatório pronto pra impressão com a identidade
 * visual do WMS em vez do CSS embutido no JSP.
 *
 * Tudo consultado AO VIVO no Sankhya a cada chamada (sem mirror local em
 * Postgres) — é um relatório de impressão sob demanda, não uma fila que
 * precisa reconciliar estado, então não se justifica o custo de manter
 * outra tabela sincronizada (mesma decisão de LiberacaoCorteService pro
 * revalidar ao vivo).
 *
 * Entidades Sankhya usadas (nomes confirmados com o usuário — não são as
 * mesmas do JSP original, que falava direto em SQL/Oracle):
 * - CabecalhoNota (TGFCAB) — já usada em outros módulos do WMS.
 * - ItemNota (TGFITE) — já usada; relação "Produto.*" já comprovada em
 *   produção (ver SeparacaoService.etapasFila), evita 2ª chamada só pra
 *   TGFPRO.
 * - OrdemCarga (TGFORD) — NOVA, nome confirmado com o usuário.
 * - Veiculo (TGFVEI) — NOVA, nome confirmado com o usuário.
 *
 * Diferente do JSP original (que faz um JOIN SQL só), aqui cada entidade é
 * buscada separada e o cruzamento é feito em memória — é o padrão já usado
 * em todo o resto do backend (loadRecords não faz JOIN entre entidades
 * quaisquer, só entre uma entidade e seus relacionamentos declarados).
 */
object MapaSeparacaoService {

    class MapaSeparacaoException(message: String) : Exception(message)

    private val FIELDS_NOTA = listOf("NUNOTA", "CODEMP", "CODPARC", "Parceiro.NOMEPARC", "TIPMOV", "ORDEMCARGA")
    private val FIELDS_ORDEM = listOf("ORDEMCARGA", "CODEMP", "PESOMAX", "CODVEICULO", "CODPARCMOTORISTA")
    private val FIELDS_ORDEM_LISTA = listOf("ORDEMCARGA", "CODEMP", "DTPREVSAIDA", "CODVEICULO", "CODPARCMOTORISTA")
    private val FIELDS_VEICULO = listOf("CODVEICULO", "MARCAMODELO", "PLACA")
    private val FIELDS_PARCEIRO = listOf("CODPARC", "NOMEPARC")
    private val FIELDS_ITEM = listOf(
        "NUNOTA", "CODPROD", "CONTROLE", "CODVOL", "QTDNEG",
        "Produto.DESCRPROD", "Produto.PESOBRUTO", "Produto.USOPROD", "Produto.AD_TIPOSEPARACAO", "Produto.CODVOL",
    )

    private data class LinhaItem(
        val nunota: Long,
        val codProd: Int,
        val controle: String?,
        val codVol: String,
        /** TGFPRO.CODVOL (cadastro) — chave do UTILICONFPESO, igual à conferência. */
        val codVolProduto: String?,
        val qtdNeg: BigDecimal,
        val descrProd: String,
        val pesoBruto: BigDecimal,
        val usoProd: String?,
        val tipoSeparacao: String,
    )

    /**
     * As 4 idas ao Sankhya (nota/ordem/veículo+motorista/itens) rodam em paralelo
     * onde a dependência permite (`coroutineScope`/`async`, mesmo padrão já usado
     * em SeparacaoService) — sequencial eram ~4 round-trips somados (relatado como
     * lento pelo usuário); só nota+ordem são independentes desde o início, e
     * veículo/motorista/itens só dependem delas, não umas das outras.
     */
    suspend fun montar(tenantSlug: String, ordemCarga: Long): MapaSeparacaoDto = coroutineScope {
        val notasRawDeferred = async {
            SankhyaLoadRecordsClient.parseRows(
                SankhyaLoadRecordsClient.loadRecords(
                    tenantSlug,
                    LoadRecordsRequest(entityName = "CabecalhoNota", fields = FIELDS_NOTA, criteriaExpression = "ORDEMCARGA = $ordemCarga"),
                ),
                FIELDS_NOTA,
            )
        }
        val ordemRawDeferred = async {
            SankhyaLoadRecordsClient.parseRows(
                SankhyaLoadRecordsClient.loadRecords(
                    tenantSlug,
                    LoadRecordsRequest(entityName = "OrdemCarga", fields = FIELDS_ORDEM, criteriaExpression = "ORDEMCARGA = $ordemCarga"),
                ),
                FIELDS_ORDEM,
            ).firstOrNull()
        }

        val notasRaw = notasRawDeferred.await()
        if (notasRaw.isEmpty()) throw MapaSeparacaoException("Nenhum pedido encontrado para a Ordem de Carga $ordemCarga")

        data class Nota(val nunota: Long, val codParc: Int, val nomeParceiro: String, val tipMov: String)
        val notas = notasRaw.mapNotNull { r ->
            val nunota = r["NUNOTA"]?.toLongOrNull() ?: return@mapNotNull null
            val codParc = r["CODPARC"]?.toIntOrNull() ?: return@mapNotNull null
            Nota(nunota, codParc, r["Parceiro.NOMEPARC"]?.trim().orEmpty(), r["TIPMOV"]?.trim().orEmpty())
        }
        if (notas.isEmpty()) throw MapaSeparacaoException("Nenhum pedido válido encontrado para a Ordem de Carga $ordemCarga")
        val notaPorNunota = notas.associateBy { it.nunota }
        val nunotas = notas.map { it.nunota }

        val ordemRaw = ordemRawDeferred.await() ?: throw MapaSeparacaoException("Ordem de Carga $ordemCarga não encontrada (TGFORD)")
        val pesoMaxOc = ordemRaw["PESOMAX"].parseBigDecimalBr()
        val codVeiculo = ordemRaw["CODVEICULO"]?.toIntOrNull()
        val codParcMotorista = ordemRaw["CODPARCMOTORISTA"]?.toIntOrNull()

        // 3 chamadas independentes entre si — só dependem da OrdemCarga/notas já resolvidas acima.
        val veiculoDeferred = codVeiculo?.let { cv ->
            async {
                SankhyaLoadRecordsClient.parseRows(
                    SankhyaLoadRecordsClient.loadRecords(
                        tenantSlug,
                        LoadRecordsRequest(entityName = "Veiculo", fields = FIELDS_VEICULO, criteriaExpression = "CODVEICULO = $cv"),
                    ),
                    FIELDS_VEICULO,
                ).firstOrNull()
            }
        }
        // Motorista é um Parceiro (TGFPAR), não TGFFUN — confirmado com o usuário
        // (TGFORD.CODPARCMOTORISTA). Mesma entidade "Parceiro" já usada pro
        // parceiro da nota (ali via relação direta; aqui via busca própria porque
        // o vínculo é o motorista da OC, não o cliente da nota).
        val motoristaDeferred = codParcMotorista?.let { cp -> async { buscarNomesParceiro(tenantSlug, listOf(cp)) } }
        val itensRawDeferred = async {
            SankhyaLoadRecordsClient.parseRows(
                SankhyaLoadRecordsClient.loadRecords(
                    tenantSlug,
                    LoadRecordsRequest(entityName = "ItemNota", fields = FIELDS_ITEM, criteriaExpression = "NUNOTA IN (${nunotas.joinToString(",")})"),
                ),
                FIELDS_ITEM,
            )
        }
        // Só depende do cadastro de unidades, não da OC — mas fica aqui (e não
        // junto de nota/ordem) pra não gastar a chamada quando a OC nem existe.
        val codvolsPesaveisDeferred = async { buscarCodvolsPesaveis(tenantSlug) }

        val veiculoRaw = veiculoDeferred?.await()
        val placa = veiculoRaw?.get("PLACA")?.trim()?.takeIf { it.isNotEmpty() }
        val modeloVeiculo = veiculoRaw?.get("MARCAMODELO")?.trim()?.takeIf { it.isNotEmpty() }
        val nomeMotorista = codParcMotorista?.let { motoristaDeferred?.await()?.get(it) }
        val itensRaw = itensRawDeferred.await()
        val codvolsPesaveis = codvolsPesaveisDeferred.await()

        val linhas = itensRaw.mapNotNull { r ->
            val nunota = r["NUNOTA"]?.toLongOrNull() ?: return@mapNotNull null
            val codProd = r["CODPROD"]?.toIntOrNull() ?: return@mapNotNull null
            LinhaItem(
                nunota = nunota,
                codProd = codProd,
                controle = r["CONTROLE"]?.trim()?.takeIf { it.isNotEmpty() },
                codVol = r["CODVOL"]?.trim().orEmpty(),
                codVolProduto = r["Produto.CODVOL"]?.trim()?.takeIf { it.isNotEmpty() },
                qtdNeg = r["QTDNEG"].parseBigDecimalBr() ?: BigDecimal.ZERO,
                descrProd = r["Produto.DESCRPROD"]?.trim().orEmpty(),
                pesoBruto = r["Produto.PESOBRUTO"].parseBigDecimalBr() ?: BigDecimal.ZERO,
                usoProd = r["Produto.USOPROD"]?.trim(),
                tipoSeparacao = r["Produto.AD_TIPOSEPARACAO"]?.trim()?.takeIf { it.isNotEmpty() } ?: "0",
            )
        }.filter { it.usoProd != "S" && it.nunota in notaPorNunota } // TGFPRO.USOPROD = 'S' — excluído, igual ao relatório original

        if (linhas.isEmpty()) throw MapaSeparacaoException("Nenhum item de separação encontrado para a Ordem de Carga $ordemCarga")

        // Mesma regra de SeparacaoService.itemUsaConfPeso: CODVOL de cadastro do
        // produto, fallback pro CODVOL da linha — ex.: queijo cadastrado em KG
        // vendido em PC continua pesável.
        fun LinhaItem.pesavel(): Boolean = (codVolProduto ?: codVol) in codvolsPesaveis

        // Devolução (TIPMOV = 'D') conta negativo — mesma lógica do iReport original preservada no JSP.
        // Aplicado por linha (antes de somar) porque agora a soma cruza pedidos.
        fun LinhaItem.qtdComSinal(): BigDecimal =
            if (notaPorNunota.getValue(nunota).tipMov == "D") qtdNeg.negate() else qtdNeg

        val (linhasPesaveis, linhasConsolidadas) = linhas.partition { it.pesavel() }

        // NÃO pesável → OC inteira, uma folha por categoria (sem quebra por pedido/parceiro).
        val consolidado = agregar(linhasConsolidadas, pesavel = false) { it.qtdComSinal() }

        // Pesável → segregado por parceiro (soma só entre os pedidos do MESMO parceiro).
        val pesaveisPorParceiro = linhasPesaveis.groupBy { notaPorNunota.getValue(it.nunota).codParc }
            .mapValues { (_, linhasParceiro) -> linhasParceiro to agregar(linhasParceiro, pesavel = true) { it.qtdComSinal() } }
        val pesaveis = pesaveisPorParceiro.map { (codParc, par) ->
            val (linhasParceiro, itens) = par
            ParceiroPesaveisDto(
                codParc = codParc,
                nomeParceiro = notaPorNunota.getValue(linhasParceiro.first().nunota).nomeParceiro,
                nunotas = linhasParceiro.map { it.nunota }.distinct().sorted(),
                quantidadeTotal = itens.sumOf { it.quantidade }.formatar(),
                pesoTotal = itens.sumOf { it.pesoTotal }.formatar(),
                categorias = categorias(itens),
            )
        }.sortedBy { it.nomeParceiro }

        val todos = consolidado + pesaveisPorParceiro.values.flatMap { it.second }
        MapaSeparacaoDto(
            ordemCarga = ordemCarga,
            codVeiculo = codVeiculo,
            placa = placa,
            modeloVeiculo = modeloVeiculo,
            codParcMotorista = codParcMotorista,
            nomeMotorista = nomeMotorista,
            pesoMaxOc = pesoMaxOc?.formatar(),
            totalPedidos = linhas.map { it.nunota }.distinct().size,
            produtosDistintos = linhas.map { it.codProd }.distinct().size,
            quantidadeTotal = todos.sumOf { it.quantidade }.formatar(),
            pesoTotal = todos.sumOf { it.pesoTotal }.formatar(),
            semClassificacao = linhas.filter { it.tipoSeparacao == "0" }.map { it.codProd }.distinct().size,
            consolidado = categorias(consolidado),
            pesaveis = pesaveis,
        )
    }

    /** Consolida por produto+controle+unidade (mesmo GROUP BY do relatório original) — sobre o conjunto de linhas recebido, não mais por nota. */
    private fun agregar(linhas: List<LinhaItem>, pesavel: Boolean, qtd: (LinhaItem) -> BigDecimal): List<ItemAgregado> {
        data class ChaveItem(val codProd: Int, val controle: String?, val codVol: String)
        return linhas.groupBy { ChaveItem(it.codProd, it.controle, it.codVol) }
            .map { (chave, itens) ->
                val total = itens.sumOf(qtd)
                val amostra = itens.first()
                ItemAgregado(
                    codProd = chave.codProd,
                    descricao = amostra.descrProd,
                    controle = chave.controle,
                    unidade = chave.codVol,
                    quantidade = total,
                    pesoUnitario = amostra.pesoBruto,
                    pesoTotal = total * amostra.pesoBruto,
                    tipoSeparacao = amostra.tipoSeparacao,
                    pesavel = pesavel,
                )
            }
    }

    private fun categorias(itens: List<ItemAgregado>): List<CategoriaSeparacaoDto> =
        listOf("1", "2", "3", "0").mapNotNull { codigo ->
            val itensCategoria = itens.filter { it.tipoSeparacao == codigo }
            if (itensCategoria.isEmpty()) return@mapNotNull null
            CategoriaSeparacaoDto(
                codigo = codigo,
                descricao = descricaoCategoria(codigo),
                quantidadeTotal = itensCategoria.sumOf { it.quantidade }.formatar(),
                pesoTotal = itensCategoria.sumOf { it.pesoTotal }.formatar(),
                itens = itensCategoria.sortedBy { it.codProd }.map { i ->
                    ItemSeparacaoDto(
                        codProd = i.codProd,
                        descricao = i.descricao,
                        controle = i.controle,
                        unidade = i.unidade,
                        quantidade = i.quantidade.formatar(),
                        pesoUnitario = i.pesoUnitario.formatar(),
                        pesoTotal = i.pesoTotal.formatar(),
                        pesavel = i.pesavel,
                    )
                },
            )
        }

    /**
     * CODVOLs com TGFVOL.UTILICONFPESO='S' (exigem pesagem) — mesma fonte da
     * conferência (SeparacaoService.buscarUtilizaConfPeso). SQL direto porque a
     * entidade "Volume" não é legível via DatasetSP. Traz todas as unidades
     * marcadas (cadastro pequeno) em vez de só as da OC pra não depender dos
     * itens e rodar em paralelo com eles.
     *
     * Falha aqui DERRUBA o relatório (vira 502) de propósito: sem saber quem é
     * pesável, o mapa somaria pesáveis de clientes diferentes numa linha só.
     */
    private suspend fun buscarCodvolsPesaveis(tenantSlug: String): Set<String> =
        SankhyaDbExplorerClient.executarQuery(tenantSlug, "SELECT CODVOL FROM TGFVOL WHERE UTILICONFPESO = 'S'")
            .mapNotNull { it["CODVOL"]?.trim()?.takeIf { cv -> cv.isNotEmpty() } }
            .toSet()

    /** status_operacional que conta como "nota conferida" pra barra de progresso — mesma família de conclusão do resto do app. */
    private val STATUS_CONFERIDA = setOf(
        StatusOperacional.CONCLUIDO.codigo,
        StatusOperacional.CONCLUIDO_DIVERGENTE.codigo,
        StatusOperacional.RECONTAGEM_CONCLUIDA.codigo,
        StatusOperacional.RECONTAGEM_CONCLUIDA_DIVERGENTE.codigo,
    )

    /**
     * Ordens de Carga ABERTAS (TGFORD.SITUACAO='A', domínio confirmado com o
     * usuário: A=Aberta, F=Fechada) — são as que ainda PRECISAM ser separadas
     * (conceito corrigido: "fechada" já foi processada/embarcada, não é o que
     * o painel deve oferecer pra separação). Enriquece placa/motorista em
     * lote (2 chamadas a mais, não 1 por OC) — mesmo padrão de `montar`.
     *
     * Progresso de conferência (totalNotas/notasConferidas) vem do MIRROR LOCAL
     * (app.tarefas, já mantido pelo TarefaSyncService) — total NÃO é "toda nota
     * vinculada à OC no Sankhya" (bug real corrigido: OC 42/46 nunca fechavam
     * 100% porque contavam nota que nunca precisou de conferência), é "toda
     * nota que já passou pelo critério de conferência", que é o mirror local
     * — ver TarefasRepository.statusPorOrdemCarga. Sem pergunta a mais ao
     * Sankhya pra isso, só ao Postgres.
     *
     * As buscas de enriquecimento (placas/motoristas) são independentes entre
     * si — rodam em paralelo (`coroutineScope`/`async`) em vez de sequenciais.
     */
    suspend fun listarAbertas(tenantSlug: String, tenantId: UUID): List<OrdemCargaResumoDto> = coroutineScope {
        val raw = SankhyaLoadRecordsClient.parseRows(
            SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(
                    entityName = "OrdemCarga",
                    fields = FIELDS_ORDEM_LISTA,
                    criteriaExpression = "SITUACAO = 'A'",
                    orderByExpression = "ORDEMCARGA DESC",
                ),
            ),
            FIELDS_ORDEM_LISTA,
        )
        if (raw.isEmpty()) return@coroutineScope emptyList()

        val ordensCarga = raw.mapNotNull { it["ORDEMCARGA"]?.toLongOrNull() }.distinct()
        val codVeiculos = raw.mapNotNull { it["CODVEICULO"]?.toIntOrNull() }.distinct()
        val codMotoristas = raw.mapNotNull { it["CODPARCMOTORISTA"]?.toIntOrNull() }.distinct()

        val placasDeferred = async { buscarPlacas(tenantSlug, codVeiculos) }
        val nomesDeferred = async { buscarNomesParceiro(tenantSlug, codMotoristas) }

        val placasPorCodVeiculo = placasDeferred.await()
        val nomesPorCodParc = nomesDeferred.await()
        val statusPorOc = withContext(Dispatchers.IO) {
            TarefasRepository.statusPorOrdemCarga(tenantId, ordensCarga.toSet())
        }.groupBy({ it.first }, { it.second })

        raw.mapNotNull { r ->
            val ordemCarga = r["ORDEMCARGA"]?.toLongOrNull() ?: return@mapNotNull null
            val codVeiculo = r["CODVEICULO"]?.toIntOrNull()
            val codMotorista = r["CODPARCMOTORISTA"]?.toIntOrNull()
            val statusDaOc = statusPorOc[ordemCarga].orEmpty()
            OrdemCargaResumoDto(
                ordemCarga = ordemCarga,
                dataPrevSaida = r["DTPREVSAIDA"]?.trim()?.takeIf { it.isNotEmpty() } ?: "—",
                placa = codVeiculo?.let { placasPorCodVeiculo[it] },
                nomeMotorista = codMotorista?.let { nomesPorCodParc[it] },
                totalNotas = statusDaOc.size,
                notasConferidas = statusDaOc.count { it in STATUS_CONFERIDA },
            )
        }
    }

    private suspend fun buscarPlacas(tenantSlug: String, codigos: List<Int>): Map<Int, String> {
        if (codigos.isEmpty()) return emptyMap()
        val raw = SankhyaLoadRecordsClient.parseRows(
            SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(entityName = "Veiculo", fields = FIELDS_VEICULO, criteriaExpression = "CODVEICULO IN (${codigos.joinToString(",")})"),
            ),
            FIELDS_VEICULO,
        )
        return raw.mapNotNull { r ->
            val cv = r["CODVEICULO"]?.toIntOrNull() ?: return@mapNotNull null
            val placa = r["PLACA"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            cv to placa
        }.toMap()
    }

    private suspend fun buscarNomesParceiro(tenantSlug: String, codigos: List<Int>): Map<Int, String> {
        if (codigos.isEmpty()) return emptyMap()
        val raw = SankhyaLoadRecordsClient.parseRows(
            SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(entityName = "Parceiro", fields = FIELDS_PARCEIRO, criteriaExpression = "CODPARC IN (${codigos.joinToString(",")})"),
            ),
            FIELDS_PARCEIRO,
        )
        return raw.mapNotNull { r ->
            val cp = r["CODPARC"]?.toIntOrNull() ?: return@mapNotNull null
            val nome = r["NOMEPARC"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            cp to nome
        }.toMap()
    }

    private data class ItemAgregado(
        val codProd: Int,
        val descricao: String,
        val controle: String?,
        val unidade: String,
        val quantidade: BigDecimal,
        val pesoUnitario: BigDecimal,
        val pesoTotal: BigDecimal,
        val tipoSeparacao: String,
        val pesavel: Boolean,
    )

    private fun descricaoCategoria(codigo: String): String = when (codigo) {
        "1" -> "SECO"
        "2" -> "REFRIGERADO"
        "3" -> "CONGELADO"
        else -> "SEM CLASSIFICAÇÃO"
    }

    private fun BigDecimal.formatar(): String = this.setScale(3, RoundingMode.HALF_UP).toPlainString()

    private fun String?.parseBigDecimalBr(): BigDecimal? = this?.trim()?.replace(",", ".")?.toBigDecimalOrNull()
}
