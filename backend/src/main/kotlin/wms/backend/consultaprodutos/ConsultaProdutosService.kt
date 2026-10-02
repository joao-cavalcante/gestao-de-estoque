package wms.backend.consultaprodutos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wms.backend.erp.LoadRecordsRequest
import wms.backend.erp.SankhyaLoadRecordsClient
import java.util.UUID

/**
 * Consulta de Produtos: acha o produto no catálogo local e lê o saldo AO VIVO na instância
 * `Estoque` do Sankhya (TGFEST) via loadRecords — nunca cacheado, estoque muda o tempo todo.
 * Ligações da instância usadas (dicionário do Sankhya): Produto, Empresa, LocalFinanceiro.
 */
object ConsultaProdutosService {

    /** Mais que isso a busca está ampla demais — o operador refina o termo. */
    private const val LIMITE = 50

    private val CAMPOS_ESTOQUE = listOf(
        "CODPROD", "CODEMP", "CODLOCAL", "CONTROLE", "ESTOQUE", "RESERVADO",
        "Produto.CODVOL", "Empresa.NOMEFANTASIA", "LocalFinanceiro.DESCRLOCAL",
    )

    suspend fun consultar(tenantSlug: String, tenantId: UUID, termo: String): ConsultaProdutosRespostaDto {
        val achados = withContext(Dispatchers.IO) { ConsultaProdutosRepository.buscar(tenantId, termo, LIMITE) }
        val limitado = achados.size > LIMITE
        val produtos = achados.take(LIMITE)
        if (produtos.isEmpty()) return ConsultaProdutosRespostaDto(emptyList())

        val (linhas, erro) = try {
            buscarEstoque(tenantSlug, produtos.map { it.codprod }) to null
        } catch (e: Exception) {
            println("AVISO: consulta de produtos — falha ao ler a instância Estoque (tenant $tenantSlug): ${e.message}")
            emptyList<Map<String, String?>>() to (e.message ?: "falha ao consultar o estoque no Sankhya")
        }
        val porProduto = linhas.groupBy { it["CODPROD"]?.toIntOrNull() }

        return ConsultaProdutosRespostaDto(
            produtos = produtos.map { p ->
                val doProduto = porProduto[p.codprod].orEmpty()
                val locais = doProduto
                    .map { r ->
                        val estoque = r["ESTOQUE"].numero()
                        val reservado = r["RESERVADO"].numero()
                        EstoqueLocalDto(
                            codemp = r["CODEMP"]?.toIntOrNull(),
                            empresa = r["Empresa.NOMEFANTASIA"]?.trim(),
                            codlocal = r["CODLOCAL"]?.toIntOrNull(),
                            local = r["LocalFinanceiro.DESCRLOCAL"]?.trim(),
                            controle = r["CONTROLE"],
                            estoque = estoque,
                            reservado = reservado,
                            disponivel = estoque - reservado,
                        )
                    }
                    // Linha zerada (local que já teve o produto) só polui a consulta.
                    .filter { it.estoque != 0.0 || it.reservado != 0.0 }
                    .sortedWith(compareBy({ it.codemp }, { it.codlocal }, { it.controle }))
                ProdutoEstoqueDto(
                    codprod = p.codprod,
                    descricao = p.descricao,
                    complemento = p.complemento,
                    marca = p.marca,
                    referencia = p.referencia,
                    unidade = doProduto.firstNotNullOfOrNull { it["Produto.CODVOL"] },
                    estoque = locais.sumOf { it.estoque },
                    reservado = locais.sumOf { it.reservado },
                    disponivel = locais.sumOf { it.disponivel },
                    locais = locais,
                )
            },
            limitado = limitado,
            erroEstoque = erro,
        )
    }

    /** Estoque PRÓPRIO (TIPO='P', sem parceiro) — estoque de terceiro não é saldo do armazém. */
    private suspend fun buscarEstoque(tenantSlug: String, codprods: List<Int>): List<Map<String, String?>> {
        val raw = SankhyaLoadRecordsClient.loadRecords(
            tenantSlug,
            LoadRecordsRequest(
                entityName = "Estoque",
                fields = CAMPOS_ESTOQUE,
                criteriaExpression = "CODPROD IN (${codprods.joinToString(",")}) AND CODPARC = 0 AND TIPO = 'P'",
            ),
        )
        return SankhyaLoadRecordsClient.parseRows(raw, CAMPOS_ESTOQUE)
    }

    private fun String?.numero(): Double = this?.trim()?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
}
