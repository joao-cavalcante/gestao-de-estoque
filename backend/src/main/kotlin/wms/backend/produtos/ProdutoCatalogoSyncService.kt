package wms.backend.produtos

import wms.backend.erp.LoadRecordsRequest
import wms.backend.erp.SankhyaDbExplorerClient
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
 * Lê a tabela inteira por SQL (DbExplorer) numa chamada só; CRUDServiceProvider paginado fica de
 * reserva se o SQL falhar — ver [lerCatalogo].
 *
 * TGFVOA fica de fora daqui de propósito — sem campo de auditoria, não compensa varrer a tabela
 * inteira; ela é populada só sob demanda (ver SeparacaoService/ProdutoCatalogoRepository.upsertVoa).
 */
private val CAMPOS_PRODUTO = listOf("CODPROD", "DESCRPROD", "COMPLDESC", "MARCA", "REFERENCIA", "CODVOL", "TIPCONTEST", "LISCONTEST", "DTALTER")
private val CAMPOS_BAR = listOf("CODPROD", "CODVOL", "CODBARRA", "DHALTER")

object ProdutoCatalogoSyncService {

    suspend fun sincronizarTenant(tenantSlug: String, tenantId: UUID): Int {
        val totalProdutos = sincronizarProdutos(tenantSlug, tenantId)
        val totalBar = sincronizarBar(tenantSlug, tenantId)
        return totalProdutos + totalBar
    }

    private suspend fun sincronizarProdutos(tenantSlug: String, tenantId: UUID): Int {
        val dtalterLocal = ProdutoCatalogoRepository.mapaDtalterProdutos(tenantId)
        val semCodvol = ProdutoCatalogoRepository.codprodsSemCodvol(tenantId)
        val alterados = mutableListOf<Map<String, String?>>()
        val vistos = mutableSetOf<Int>()
        lerCatalogo(tenantSlug, "Produto", "TGFPRO", CAMPOS_PRODUTO, "DTALTER").forEach { linha ->
            val codprod = linha["CODPROD"]?.toIntOrNull()
            if (codprod != null) {
                vistos += codprod
                if (!dtalterLocal.containsKey(codprod) || dtalterLocal[codprod] != linha["DTALTER"] ||
                    (codprod in semCodvol && !linha["CODVOL"].isNullOrBlank())
                ) {
                    alterados += linha
                }
            }
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
        lerCatalogo(tenantSlug, "CodigoBarras", "TGFBAR", CAMPOS_BAR, "DHALTER").forEach { linha ->
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

        // Mesmo raciocínio de sincronizarProdutos — código de barras que sumiu de todas as
        // páginas foi excluído/retirado no Sankhya, não pode continuar "válido" pra sempre local.
        val removidos = dhalterLocal.keys - vistos
        if (removidos.isNotEmpty()) ProdutoCatalogoRepository.removerBarPorChave(tenantId, removidos)

        return if (alterados.isEmpty()) 0 else ProdutoCatalogoRepository.upsertBar(tenantId, alterados)
    }

    /**
     * A tabela inteira de uma vez via SQL (DbExplorer: ~1,5s pros 1.487 produtos da negri) em vez de
     * paginar o CRUDServiceProvider de ~50 em ~50 (67 chamadas, ~70s de Sankhya ocupado a cada 15 min,
     * disputando com a conferência). Falhou o SQL (ex.: tenant sem permissão no DbExplorer) → paginação antiga.
     * A data de auditoria volta no formato do CRUD ("dd/MM/yyyy HH:mm:ss") — é o que está gravado local
     * e o que a comparação usa; sem isso a 1ª passada regravaria o catálogo inteiro.
     */
    private suspend fun lerCatalogo(tenantSlug: String, entidade: String, tabela: String, campos: List<String>, campoData: String): List<Map<String, String?>> {
        val viaSql = runCatching {
            val linhas = SankhyaDbExplorerClient.executarQuery(tenantSlug, "SELECT ${campos.joinToString(", ")} FROM $tabela")
            // Varredura completa decide REMOÇÃO local — resposta cortada (limite de linhas) apagaria catálogo.
            val total = SankhyaDbExplorerClient.executarQuery(tenantSlug, "SELECT COUNT(*) AS N FROM $tabela")
                .firstOrNull()?.get("N")?.toBigDecimalOrNull()?.toInt()
            check(total == linhas.size) { "SQL devolveu ${linhas.size} de $total linhas" }
            linhas.map { linha -> linha + (campoData to dataNoFormatoCrud(linha[campoData])) }
        }.onFailure { println("AVISO: sync catálogo $tabela via SQL falhou ($tenantSlug) — paginando: ${it.message}") }
            .getOrNull()
        if (viaSql != null) return viaSql

        val linhas = mutableListOf<Map<String, String?>>()
        var pagina = 0
        while (true) {
            val raw = SankhyaLoadRecordsClient.loadRecords(
                tenantSlug,
                LoadRecordsRequest(entityName = entidade, fields = campos, usarCrudServiceProvider = true, offsetPage = pagina),
            )
            linhas += SankhyaLoadRecordsClient.parseRows(raw, campos)
            if (!SankhyaLoadRecordsClient.hasMoreResult(raw)) break
            pagina++
        }
        return linhas
    }

    /** "23092026 08:30:47" (DbExplorer) → "23/09/2026 08:30:47" (CRUDServiceProvider). Outro formato passa direto. */
    private fun dataNoFormatoCrud(v: String?): String? {
        val m = v?.let { Regex("""^(\d{2})(\d{2})(\d{4})( .*)?$""").find(it.trim()) } ?: return v
        val (d, mes, a, hora) = m.destructured
        return "$d/$mes/$a$hora"
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
