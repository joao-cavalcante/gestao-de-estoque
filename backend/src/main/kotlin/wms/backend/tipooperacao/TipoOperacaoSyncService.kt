package wms.backend.tipooperacao

import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.tenancy.TenantRepository
import java.util.UUID

/**
 * Espelho local de Tipos de Operação lido DIRETO do Sankhya (TGFTOP via DbExplorerSP): só TOPs
 * ATIVOS com NUCCO preenchido (os que têm Configuração de Conferência), na versão mais recente
 * (TGFTOP guarda uma linha por DHALTER).
 *
 * Antes a lista era derivada de app.tarefas (CODTIPOPER dos pedidos da Fila) — sumia inteira quando
 * a Fila esvaziava (caso real: limpeza dos pedidos da Negri em 30/09, base do Sankhya zerada). A
 * entidade TipoOperacao via loadRecords era incompleta (não listava o 1011), por isso SQL direto.
 */
object TipoOperacaoSyncService {

    private const val SQL_TOPS_COM_NUCCO =
        "SELECT T.CODTIPOPER, T.DESCROPER, T.NUCCO, T.TIPMOV FROM TGFTOP T " +
            "WHERE T.NUCCO IS NOT NULL AND T.ATIVO = 'S' " +
            "AND T.DHALTER = (SELECT MAX(T2.DHALTER) FROM TGFTOP T2 WHERE T2.CODTIPOPER = T.CODTIPOPER)"

    suspend fun sincronizarTenant(tenantSlug: String, tenantId: UUID): Int {
        val linhas = SankhyaDbExplorerClient.executarQuery(tenantSlug, SQL_TOPS_COM_NUCCO)
        val tops = linhas.mapNotNull { r ->
            val codtop = r["CODTIPOPER"]?.trim()?.toIntOrNull() ?: return@mapNotNull null
            TopDerivado(
                codtop = codtop,
                descricao = r["DESCROPER"]?.trim()?.takeIf { it.isNotEmpty() } ?: "Tipo de Operação $codtop",
                nucco = r["NUCCO"]?.trim()?.toIntOrNull(),
                tipmov = r["TIPMOV"]?.trim()?.takeIf { it.isNotEmpty() },
            )
        }.distinctBy { it.codtop }
        // Sankhya respondeu vazio (ex.: conexão ok mas consulta sem retorno) — não apaga a lista local.
        if (tops.isEmpty()) return 0
        val total = TipoOperacaoRepository.substituirDerivado(tenantId, tops)
        // Destinos do faturamento de cada TOP — falha aqui não derruba a sincronização das TOPs.
        runCatching {
            val origens = tops.map { it.codtop }.toSet()
            TipoOperacaoRepository.substituirDestinos(tenantId, origens, buscarDestinos(tenantSlug, origens))
        }.onFailure { println("AVISO: sync das TOPs de destino falhou ($tenantSlug): ${it.message}") }
        return total
    }

    /**
     * Restrições de destino da TOP (instância RestricaoTop = TGFREP): TIPREST 'D' com RESTRICAO 'S' — CODCOLREST é a
     * TOP de destino permitida no faturamento. Só destino ATIVO, na versão mais recente da TGFTOP.
     */
    suspend fun buscarDestinos(tenantSlug: String, origens: Collection<Int>): Map<Int, List<TopDestinoDto>> {
        if (origens.isEmpty()) return emptyMap()
        return SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT R.CODTIPOPER, R.CODCOLREST, R.SERIE, T.DESCROPER FROM TGFREP R " +
                "JOIN TGFTOP T ON T.CODTIPOPER = R.CODCOLREST AND T.DHALTER = (SELECT MAX(X.DHALTER) FROM TGFTOP X WHERE X.CODTIPOPER = R.CODCOLREST) " +
                "WHERE R.TIPREST = 'D' AND R.RESTRICAO = 'S' AND T.ATIVO = 'S' AND R.CODTIPOPER IN (${origens.joinToString(",")})",
        ).mapNotNull { r ->
            val origem = r["CODTIPOPER"]?.toBigDecimalOrNull()?.toInt() ?: return@mapNotNull null
            val destino = r["CODCOLREST"]?.toBigDecimalOrNull()?.toInt()?.takeIf { it > 0 } ?: return@mapNotNull null
            origem to TopDestinoDto(destino, r["DESCROPER"]?.trim()?.takeIf { it.isNotEmpty() } ?: "TOP $destino", r["SERIE"]?.trim()?.takeIf { it.isNotEmpty() })
        }.groupBy({ it.first }, { it.second })
    }

    /** Chamado pelo worker periódico — tenant sem Sankhya/fora do ar é ignorado até o próximo ciclo. */
    suspend fun sincronizarTodosOsTenants() {
        TenantRepository.listar().forEach { tenant ->
            val tenantId = tenant.id?.let { UUID.fromString(it) } ?: return@forEach
            try {
                sincronizarTenant(tenant.slug, tenantId)
            } catch (e: Exception) {
                // próximo ciclo tenta de novo
            }
        }
    }
}
