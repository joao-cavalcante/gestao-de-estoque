package wms.backend.mapaseparacao

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wms.backend.erp.LoadRecordsRequest
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
        "Produto.DESCRPROD", "Produto.PESOBRUTO", "Produto.USOPROD", "Produto.AD_TIPOSEPARACAO",
    )

    private data class LinhaItem(
        val nunota: Long,
        val codProd: Int,
        val controle: String?,
        val codVol: String,
        val qtdNeg: BigDecimal,
        val descrProd: String,
        val pesoBruto: BigDecimal,
        val usoProd: String?,
        val tipoSeparacao: String,
    )

    suspend fun montar(tenantSlug: String, ordemCarga: Long): MapaSeparacaoDto {
        val notasRaw = SankhyaLoadRecordsClient.parseRows(
            SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(entityName = "CabecalhoNota", fields = FIELDS_NOTA, criteriaExpression = "ORDEMCARGA = $ordemCarga"),
            ),
            FIELDS_NOTA,
        )
        if (notasRaw.isEmpty()) throw MapaSeparacaoException("Nenhum pedido encontrado para a Ordem de Carga $ordemCarga")

        data class Nota(val nunota: Long, val codParc: Int, val nomeParceiro: String, val tipMov: String)
        val notas = notasRaw.mapNotNull { r ->
            val nunota = r["NUNOTA"]?.toLongOrNull() ?: return@mapNotNull null
            val codParc = r["CODPARC"]?.toIntOrNull() ?: return@mapNotNull null
            Nota(nunota, codParc, r["Parceiro.NOMEPARC"]?.trim().orEmpty(), r["TIPMOV"]?.trim().orEmpty())
        }
        if (notas.isEmpty()) throw MapaSeparacaoException("Nenhum pedido válido encontrado para a Ordem de Carga $ordemCarga")
        val notaPorNunota = notas.associateBy { it.nunota }

        val ordemRaw = SankhyaLoadRecordsClient.parseRows(
            SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(entityName = "OrdemCarga", fields = FIELDS_ORDEM, criteriaExpression = "ORDEMCARGA = $ordemCarga"),
            ),
            FIELDS_ORDEM,
        ).firstOrNull() ?: throw MapaSeparacaoException("Ordem de Carga $ordemCarga não encontrada (TGFORD)")

        val pesoMaxOc = ordemRaw["PESOMAX"].parseBigDecimalBr()
        val codVeiculo = ordemRaw["CODVEICULO"]?.toIntOrNull()
        val codParcMotorista = ordemRaw["CODPARCMOTORISTA"]?.toIntOrNull()

        var placa: String? = null
        var modeloVeiculo: String? = null
        if (codVeiculo != null) {
            val veiculoRaw = SankhyaLoadRecordsClient.parseRows(
                SankhyaLoadRecordsClient.loadRecords(
                    tenantSlug,
                    LoadRecordsRequest(entityName = "Veiculo", fields = FIELDS_VEICULO, criteriaExpression = "CODVEICULO = $codVeiculo"),
                ),
                FIELDS_VEICULO,
            ).firstOrNull()
            placa = veiculoRaw?.get("PLACA")?.trim()?.takeIf { it.isNotEmpty() }
            modeloVeiculo = veiculoRaw?.get("MARCAMODELO")?.trim()?.takeIf { it.isNotEmpty() }
        }

        // Motorista é um Parceiro (TGFPAR), não TGFFUN — confirmado com o usuário
        // (TGFORD.CODPARCMOTORISTA). Mesma entidade "Parceiro" já usada pro
        // parceiro da nota (ali via relação direta; aqui via busca própria porque
        // o vínculo é o motorista da OC, não o cliente da nota).
        val nomeMotorista = codParcMotorista?.let { buscarNomesParceiro(tenantSlug, listOf(it))[it] }

        val nunotas = notas.map { it.nunota }
        val itensRaw = SankhyaLoadRecordsClient.parseRows(
            SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(entityName = "ItemNota", fields = FIELDS_ITEM, criteriaExpression = "NUNOTA IN (${nunotas.joinToString(",")})"),
            ),
            FIELDS_ITEM,
        )

        val linhas = itensRaw.mapNotNull { r ->
            val nunota = r["NUNOTA"]?.toLongOrNull() ?: return@mapNotNull null
            val codProd = r["CODPROD"]?.toIntOrNull() ?: return@mapNotNull null
            LinhaItem(
                nunota = nunota,
                codProd = codProd,
                controle = r["CONTROLE"]?.trim()?.takeIf { it.isNotEmpty() },
                codVol = r["CODVOL"]?.trim().orEmpty(),
                qtdNeg = r["QTDNEG"].parseBigDecimalBr() ?: BigDecimal.ZERO,
                descrProd = r["Produto.DESCRPROD"]?.trim().orEmpty(),
                pesoBruto = r["Produto.PESOBRUTO"].parseBigDecimalBr() ?: BigDecimal.ZERO,
                usoProd = r["Produto.USOPROD"]?.trim(),
                tipoSeparacao = r["Produto.AD_TIPOSEPARACAO"]?.trim()?.takeIf { it.isNotEmpty() } ?: "0",
            )
        }.filter { it.usoProd != "S" } // TGFPRO.USOPROD = 'S' — excluído, igual ao relatório original

        val notasDto = nunotas.mapNotNull { nunota ->
            val nota = notaPorNunota[nunota] ?: return@mapNotNull null
            val linhasNota = linhas.filter { it.nunota == nunota }
            if (linhasNota.isEmpty()) return@mapNotNull null

            // Devolução (TIPMOV = 'D') conta negativo — mesma lógica do iReport original preservada no JSP.
            val sinal = if (nota.tipMov == "D") BigDecimal.valueOf(-1) else BigDecimal.ONE

            // Consolida por produto+controle+unidade dentro da nota (mesmo GROUP BY do relatório original).
            data class ChaveItem(val codProd: Int, val controle: String?, val codVol: String)
            val consolidado = linhasNota.groupBy { ChaveItem(it.codProd, it.controle, it.codVol) }
                .map { (chave, itens) ->
                    val qtd = itens.sumOf { it.qtdNeg } * sinal
                    val amostra = itens.first()
                    ItemAgregado(
                        codProd = chave.codProd,
                        descricao = amostra.descrProd,
                        controle = chave.controle,
                        unidade = chave.codVol,
                        quantidade = qtd,
                        pesoUnitario = amostra.pesoBruto,
                        pesoTotal = qtd * amostra.pesoBruto,
                        tipoSeparacao = amostra.tipoSeparacao,
                    )
                }

            val categorias = listOf("1", "2", "3", "0").mapNotNull { codigo ->
                val itensCategoria = consolidado.filter { it.tipoSeparacao == codigo }
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
                        )
                    },
                )
            }

            NotaSeparacaoDto(
                nunota = nunota,
                codParc = nota.codParc,
                nomeParceiro = nota.nomeParceiro,
                codVeiculo = codVeiculo,
                placa = placa,
                modeloVeiculo = modeloVeiculo,
                codParcMotorista = codParcMotorista,
                nomeMotorista = nomeMotorista,
                pesoMaxOc = pesoMaxOc?.formatar(),
                produtosDistintos = consolidado.map { it.codProd }.distinct().size,
                quantidadeTotal = consolidado.sumOf { it.quantidade }.formatar(),
                pesoTotal = consolidado.sumOf { it.pesoTotal }.formatar(),
                semClassificacao = consolidado.count { it.tipoSeparacao == "0" },
                categorias = categorias,
            )
        }

        if (notasDto.isEmpty()) throw MapaSeparacaoException("Nenhum item de separação encontrado para a Ordem de Carga $ordemCarga")

        return MapaSeparacaoDto(ordemCarga = ordemCarga, notas = notasDto)
    }

    /** status_operacional que conta como "nota conferida" pra barra de progresso — mesma família de conclusão do resto do app. */
    private val STATUS_CONFERIDA = setOf(
        StatusOperacional.CONCLUIDO.codigo,
        StatusOperacional.CONCLUIDO_DIVERGENTE.codigo,
        StatusOperacional.RECONTAGEM_CONCLUIDA.codigo,
        StatusOperacional.RECONTAGEM_CONCLUIDA_DIVERGENTE.codigo,
    )

    /**
     * Ordens de Carga FECHADAS (TGFORD.SITUACAO='F', domínio confirmado com o
     * usuário: A=Aberta, F=Fechada) — pra tela oferecer uma lista pronta em vez
     * do operador ter que saber o número de cor. Enriquece placa/motorista em
     * lote (2 chamadas a mais, não 1 por OC) — mesmo padrão de `montar`.
     *
     * Progresso de conferência (totalNotas/notasConferidas) vem do MIRROR LOCAL
     * (app.tarefas, já mantido pelo TarefaSyncService) — não é mais uma 2ª
     * pergunta ao Sankhya sobre status de conferência, reaproveita o que a
     * Fila de Tarefas já sincroniza.
     */
    suspend fun listarFechadas(tenantSlug: String, tenantId: UUID): List<OrdemCargaResumoDto> {
        val raw = SankhyaLoadRecordsClient.parseRows(
            SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(
                    entityName = "OrdemCarga",
                    fields = FIELDS_ORDEM_LISTA,
                    criteriaExpression = "SITUACAO = 'F'",
                    orderByExpression = "ORDEMCARGA DESC",
                ),
            ),
            FIELDS_ORDEM_LISTA,
        )
        if (raw.isEmpty()) return emptyList()

        val ordensCarga = raw.mapNotNull { it["ORDEMCARGA"]?.toLongOrNull() }.distinct()
        val codVeiculos = raw.mapNotNull { it["CODVEICULO"]?.toIntOrNull() }.distinct()
        val codMotoristas = raw.mapNotNull { it["CODPARCMOTORISTA"]?.toIntOrNull() }.distinct()
        val placasPorCodVeiculo = buscarPlacas(tenantSlug, codVeiculos)
        val nomesPorCodParc = buscarNomesParceiro(tenantSlug, codMotoristas)
        val nunotasPorOrdemCarga = buscarNunotasPorOrdemCarga(tenantSlug, ordensCarga)
        val todasNunotas = nunotasPorOrdemCarga.values.flatten()
        val statusPorNunota = withContext(Dispatchers.IO) {
            TarefasRepository.statusOperacionalPorNunotas(tenantId, todasNunotas)
        }

        return raw.mapNotNull { r ->
            val ordemCarga = r["ORDEMCARGA"]?.toLongOrNull() ?: return@mapNotNull null
            val codVeiculo = r["CODVEICULO"]?.toIntOrNull()
            val codMotorista = r["CODPARCMOTORISTA"]?.toIntOrNull()
            val nunotasDaOc = nunotasPorOrdemCarga[ordemCarga].orEmpty()
            OrdemCargaResumoDto(
                ordemCarga = ordemCarga,
                dataPrevSaida = r["DTPREVSAIDA"]?.trim()?.takeIf { it.isNotEmpty() } ?: "—",
                placa = codVeiculo?.let { placasPorCodVeiculo[it] },
                nomeMotorista = codMotorista?.let { nomesPorCodParc[it] },
                totalNotas = nunotasDaOc.size,
                notasConferidas = nunotasDaOc.count { statusPorNunota[it] in STATUS_CONFERIDA },
            )
        }
    }

    /** NUNOTA de TGFCAB por ORDEMCARGA, em lote — usado só pra contar progresso, não carrega mais campos que isso. */
    private suspend fun buscarNunotasPorOrdemCarga(tenantSlug: String, ordensCarga: List<Long>): Map<Long, List<Long>> {
        if (ordensCarga.isEmpty()) return emptyMap()
        val fields = listOf("NUNOTA", "ORDEMCARGA")
        val raw = SankhyaLoadRecordsClient.parseRows(
            SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(entityName = "CabecalhoNota", fields = fields, criteriaExpression = "ORDEMCARGA IN (${ordensCarga.joinToString(",")})"),
            ),
            fields,
        )
        return raw.mapNotNull { r ->
            val nunota = r["NUNOTA"]?.toLongOrNull() ?: return@mapNotNull null
            val ordemCarga = r["ORDEMCARGA"]?.toLongOrNull() ?: return@mapNotNull null
            ordemCarga to nunota
        }.groupBy({ it.first }, { it.second })
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
