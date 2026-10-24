package wms.backend.separacao

/**
 * Critério que divide a conferência segmentada em etapas (e define o que já sai carregado da conferência).
 *
 * - PESO (ativo): 4 Não pesáveis | 5 Pesáveis — mesma regra da pesagem ([wms.backend.produtos.RegraPesavel]).
 *   Pesável segue a regra do Refrigerado: vai pro checklist de carregamento.
 * - TEMPERATURA (desativado, mantido pra voltar): TGFPRO.AD_TIPOSEPARACAO — 1 Secos | 2 Refrigerado | 3 Congelado.
 *
 * Voltar pro método antigo: WMS_CRITERIO_ETAPA=TEMPERATURA. Vale pras conferências abertas daqui pra frente;
 * sessão já aberta mantém os códigos com que nasceu. O Mapa de Separação não usa isto (continua por temperatura).
 */
object CriterioEtapa {
    enum class Criterio { TEMPERATURA, PESO }

    val ATIVO: Criterio = System.getenv("WMS_CRITERIO_ETAPA")?.trim()?.uppercase()
        ?.let { v -> Criterio.entries.firstOrNull { it.name == v } } ?: Criterio.PESO

    const val NAO_PESAVEL: Short = 4
    const val PESAVEL: Short = 5

    /** Etapas do critério ativo, na ordem da conferência. */
    val TIPOS_ATIVOS: List<Int> = if (ATIVO == Criterio.PESO) listOf(4, 5) else listOf(1, 2, 3)

    /**
     * Etapas conferidas já colocando no caminhão — viram "carregado" ao concluir. Refrigerado (2) e
     * Pesáveis (5) ficam pro checklist. Os códigos antigos continuam valendo pras sessões antigas.
     */
    val CARREGA_NA_CONFERENCIA: Set<Int> = setOf(1, 3, 4)

    /**
     * Etapa do item. [adTipoSeparacao] = TGFPRO.AD_TIPOSEPARACAO já normalizado (1..3). [porPeso] = critério
     * da sessão (padrão: o ativo) — a sincronização com o Sankhya mantém o critério com que a sessão abriu.
     */
    fun tipo(adTipoSeparacao: Short, pesavel: Boolean, porPeso: Boolean = ATIVO == Criterio.PESO): Short =
        if (porPeso) (if (pesavel) PESAVEL else NAO_PESAVEL) else adTipoSeparacao

    /** Critério com que a sessão abriu, pelos tipos dos itens dela (vazia = o ativo). */
    fun sessaoPorPeso(tiposDaSessao: Collection<Int>): Boolean =
        if (tiposDaSessao.isEmpty()) ATIVO == Criterio.PESO else tiposDaSessao.any { it >= NAO_PESAVEL }

    /** Etapa padrão de item sem classificação (produto fora do pedido em conferência sem etapa ativa). */
    val TIPO_PADRAO: Short get() = tipo(1, pesavel = false)

    fun nome(tipo: Int): String = when (tipo) {
        2 -> "Refrigerado"
        3 -> "Congelado"
        4 -> "Não pesáveis"
        5 -> "Pesáveis"
        else -> "Secos"
    }
}
