package wms.backend.produtos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.tenancy.Modulos
import wms.backend.tenancy.TenantRepository
import java.util.UUID

/**
 * Regra ÚNICA de "o produto é pesável?" (exige pesagem na conferência, etiqueta de peso,
 * balança no Mapa de Separação). Quem decide é daqui; o resto do sistema só lê o resultado
 * gravado por item (separacao_itens.usa_conf_peso) ou o `pesavel` do mapa.
 *
 * - Padrão (todos os tenants): unidade do produto com TGFVOL.UTILICONFPESO = 'S' — CODVOL de
 *   cadastro (TGFPRO.CODVOL), com fallback pro CODVOL da linha só se o do produto não existir
 *   (igual ao fila-de-conferencia; bug do queijo 3832 cadastrado em KG e vendido em PC).
 * - Módulo [Modulos.PESAVEL_POR_PRODUTO] (Negri, exigência do cliente): SÓ TGFPRO.AD_PESAVEL = 'S'.
 *   A unidade (TGFVOL) não entra. 'N', nulo ou vazio = não pesável (nulo/vazio gera AVISO no log —
 *   é cadastro faltando, não pode passar batido). O campo só é consultado com o módulo ligado:
 *   tenant sem AD_PESAVEL no dicionário nunca chega nessa query.
 */
object RegraPesavel {

    /** Decisão pura (sem Sankhya) — o que os testes cobrem. */
    fun ehPesavel(
        porProduto: Boolean,
        adPesavel: String?,
        codvolProduto: String?,
        codvolLinha: String?,
        codvolsPesaveis: Set<String>,
    ): Boolean {
        if (porProduto) return adPesavel?.trim()?.uppercase() == "S"
        val chave = codvolProduto?.trim()?.takeIf { it.isNotEmpty() } ?: codvolLinha?.trim()?.takeIf { it.isNotEmpty() }
        return chave != null && chave in codvolsPesaveis
    }

    /** Pergunta "este item (produto + CODVOL da linha) é pesável?", já com os dados do Sankhya carregados. */
    fun interface Decisor {
        fun pesavel(codprod: Int, codvolLinha: String?): Boolean
    }

    /** Nenhum item pesável — fallback de quem não pode travar por falha de leitura (ex.: abrir conferência). */
    val NENHUM = Decisor { _, _ -> false }

    /** Filtro SQL de produtos por lista de códigos (só inteiros — sem risco de injeção). */
    fun filtroCodprods(codprods: Collection<Int>): String? =
        codprods.distinct().takeIf { it.isNotEmpty() }?.let { "CODPROD IN (${it.joinToString(",")})" }

    /**
     * Carrega do Sankhya o que a regra do tenant precisa, pros produtos que casam com
     * [filtroProdutos] (predicado SQL sobre TGFPRO, ex.: [filtroCodprods] ou um subselect de
     * TGFITE — o Mapa usa subselect pra rodar em paralelo com os itens). SQL direto
     * (DbExplorer): TGFVOL não é legível via DatasetSP (ver SeparacaoService). Falha de leitura
     * PROPAGA — quem chama decide se trava (Mapa) ou segue sem peso (conferência).
     */
    suspend fun decisor(tenantSlug: String, tenantId: UUID, filtroProdutos: String?): Decisor {
        if (filtroProdutos == null) return NENHUM
        val porProduto = withContext(Dispatchers.IO) {
            TenantRepository.modulosHabilitados(tenantId).contains(Modulos.PESAVEL_POR_PRODUTO)
        }
        return if (porProduto) decisorPorProduto(tenantSlug, filtroProdutos) else decisorPorUnidade(tenantSlug, filtroProdutos)
    }

    private suspend fun decisorPorProduto(tenantSlug: String, filtroProdutos: String): Decisor {
        val adPesavelPorCodprod = SankhyaDbExplorerClient.executarQuery(tenantSlug, "SELECT CODPROD, AD_PESAVEL FROM TGFPRO WHERE $filtroProdutos")
            .mapNotNull { r -> r["CODPROD"]?.toIntOrNull()?.let { it to r["AD_PESAVEL"] } }
            .toMap()
        val semCadastro = adPesavelPorCodprod.filterValues { it.isNullOrBlank() }.keys
        if (semCadastro.isNotEmpty()) {
            println("AVISO: TGFPRO.AD_PESAVEL nulo/vazio (tenant $tenantSlug) — tratado como NÃO pesável: produtos ${semCadastro.sorted()}")
        }
        return Decisor { codprod, _ ->
            ehPesavel(porProduto = true, adPesavel = adPesavelPorCodprod[codprod], codvolProduto = null, codvolLinha = null, codvolsPesaveis = emptySet())
        }
    }

    private suspend fun decisorPorUnidade(tenantSlug: String, filtroProdutos: String): Decisor {
        // CODVOL de cadastro: falha aqui não derruba (cai no CODVOL da linha, como antes).
        val codvolProduto = runCatching {
            SankhyaDbExplorerClient.executarQuery(tenantSlug, "SELECT CODPROD, CODVOL FROM TGFPRO WHERE $filtroProdutos")
                .mapNotNull { r ->
                    val codprod = r["CODPROD"]?.toIntOrNull() ?: return@mapNotNull null
                    val codvol = r["CODVOL"] ?: return@mapNotNull null
                    codprod to codvol
                }
                .toMap()
        }.onFailure { println("AVISO: falha ao ler TGFPRO.CODVOL (tenant $tenantSlug): ${it.message}") }
            .getOrDefault(emptyMap())
        // Cadastro de unidades é pequeno — todas as marcadas, sem depender dos codvols dos itens.
        val codvolsPesaveis = SankhyaDbExplorerClient.executarQuery(tenantSlug, "SELECT CODVOL FROM TGFVOL WHERE UTILICONFPESO = 'S'")
            .mapNotNull { it["CODVOL"]?.trim()?.takeIf { cv -> cv.isNotEmpty() } }
            .toSet()
        return Decisor { codprod, codvolLinha ->
            ehPesavel(porProduto = false, adPesavel = null, codvolProduto = codvolProduto[codprod], codvolLinha = codvolLinha, codvolsPesaveis = codvolsPesaveis)
        }
    }
}
