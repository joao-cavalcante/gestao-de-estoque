package wms.backend.produtos

import wms.backend.erp.LoadRecordsRequest
import wms.backend.erp.SankhyaLoadRecordsClient
import wms.backend.tenancy.TenantRepository
import java.util.UUID

/**
 * Sync incremental de verdade via DTALTER (TGFPRO) / DHALTER (TGFBAR) — mesma técnica de
 * TipoOperacaoSyncService (V16/V17): pagina TODO o catálogo (uma passada só, com os campos
 * completos), compara o campo de auditoria de cada linha com o que já está local, e só faz
 * upsert de quem mudou ou é novo. NÃO faz uma segunda consulta "só dos que mudaram" — pra um
 * catálogo de 16k+ produtos isso viraria um `CODPROD IN (...)` gigante (todo mundo muda na
 * primeira sincronização, por exemplo), então é mais seguro e mais simples comparar linha a
 * linha na mesma passada de paginação.
 *
 * Via CRUDServiceProvider (paginação real com offsetPage/hasMoreResult) — NÃO
 * SankhyaDbExplorerClient, reservado pro caso excepcional de TGFEST (estoque ao vivo).
 *
 * TGFVOA fica de fora daqui de propósito — sem campo de auditoria, não compensa varrer a tabela
 * inteira; ela é populada só sob demanda (ver SeparacaoService/ProdutoCatalogoRepository.upsertVoa).
 */
private val CAMPOS_PRODUTO = listOf("CODPROD", "DESCRPROD", "COMPLDESC", "MARCA", "REFERENCIA", "TIPCONTEST", "LISCONTEST", "DTALTER")
private val CAMPOS_BAR = listOf("CODPROD", "CODVOL", "CODBARRA", "DHALTER")

object ProdutoCatalogoSyncService {

    suspend fun sincronizarTenant(tenantSlug: String, tenantId: UUID): Int {
        val totalProdutos = sincronizarProdutos(tenantSlug, tenantId)
        val totalBar = sincronizarBar(tenantSlug, tenantId)
        return totalProdutos + totalBar
    }

    private suspend fun sincronizarProdutos(tenantSlug: String, tenantId: UUID): Int {
        val dtalterLocal = ProdutoCatalogoRepository.mapaDtalterProdutos(tenantId)
        val alterados = mutableListOf<Map<String, String?>>()
        val vistos = mutableSetOf<Int>()
        var pagina = 0
        while (true) {
            val raw = SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(entityName = "Produto", fields = CAMPOS_PRODUTO, usarCrudServiceProvider = true, offsetPage = pagina),
            )
            SankhyaLoadRecordsClient.parseRows(raw, CAMPOS_PRODUTO).forEach { linha ->
                val codprod = linha["CODPROD"]?.toIntOrNull()
                if (codprod != null) {
                    vistos += codprod
                    if (!dtalterLocal.containsKey(codprod) || dtalterLocal[codprod] != linha["DTALTER"]) {
                        alterados += linha
                    }
                }
            }
            if (!SankhyaLoadRecordsClient.hasMoreResult(raw)) break
            pagina++
        }

        // Quem estava local mas não apareceu em NENHUMA página desta varredura completa foi
        // removido/desativado no Sankhya — sem isso o mirror nunca esquece produto excluído.
        val removidos = dtalterLocal.keys - vistos
        if (removidos.isNotEmpty()) ProdutoCatalogoRepository.removerProdutosPorCodigo(tenantId, removidos)

        return if (alterados.isEmpty()) 0 else ProdutoCatalogoRepository.upsertProdutos(tenantId, alterados)
    }

    private suspend fun sincronizarBar(tenantSlug: String, tenantId: UUID): Int {
        val dhalterLocal = ProdutoCatalogoRepository.mapaDhalterBar(tenantId)
        val alterados = mutableListOf<Map<String, String?>>()
        val vistos = mutableSetOf<Triple<Int, String, String>>()
        var pagina = 0
        while (true) {
            val raw = SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(entityName = "CodigoBarras", fields = CAMPOS_BAR, usarCrudServiceProvider = true, offsetPage = pagina),
            )
            SankhyaLoadRecordsClient.parseRows(raw, CAMPOS_BAR).forEach { linha ->
                val codprod = linha["CODPROD"]?.toIntOrNull()
                val codbarra = linha["CODBARRA"]?.trim()
                if (codprod != null && !codbarra.isNullOrEmpty()) {
                    val chave = Triple(codprod, linha["CODVOL"] ?: "", codbarra)
                    vistos += chave
                    if (!dhalterLocal.containsKey(chave) || dhalterLocal[chave] != linha["DHALTER"]) {
                        alterados += linha
                    }
                }
            }
            if (!SankhyaLoadRecordsClient.hasMoreResult(raw)) break
            pagina++
        }

        // Mesmo raciocínio de sincronizarProdutos — código de barras que sumiu de todas as
        // páginas foi excluído/retirado no Sankhya, não pode continuar "válido" pra sempre local.
        val removidos = dhalterLocal.keys - vistos
        if (removidos.isNotEmpty()) ProdutoCatalogoRepository.removerBarPorChave(tenantId, removidos)

        return if (alterados.isEmpty()) 0 else ProdutoCatalogoRepository.upsertBar(tenantId, alterados)
    }

    /** Chamado pelo worker periódico — ignora silenciosamente tenant sem conexão Sankhya configurada. */
    suspend fun sincronizarTodosOsTenants() {
        TenantRepository.listar().forEach { tenant ->
            val slug = tenant.slug
            val tenantId = tenant.id?.let { UUID.fromString(it) } ?: return@forEach
            try {
                sincronizarTenant(slug, tenantId)
            } catch (e: Exception) {
                // tenant sem Sankhya configurado, ou temporariamente fora do ar — próximo ciclo tenta de novo
            }
        }
    }
}
