package wms.backend.tenancy

/**
 * Registro central dos nomes de MÓDULO por-tenant — os valores válidos pra
 * `tenancy.erp_connections.modulos text[]` (feature flags habilitadas pro
 * tenant, ver V1__tenancy_foundation.sql).
 *
 * Módulo != configuração de conferência (TGFCCO, vinda do Sankhya): módulo é
 * conceito do WMS, ligado/desligado só pela plataforma (master), e serve pra
 * isolar comportamento que só um cliente usa. Ver TenantRepository.modulosHabilitados.
 */
object Modulos {
    /** Conferência segmentada — conceito exclusivo de um cliente; nenhum outro tenant enxerga. */
    const val CONFERENCIA_SEGMENTADA = "conferencia_segmentada"

    /**
     * Pesável pelo PRODUTO (TGFPRO.AD_PESAVEL = 'S') em vez da unidade (TGFVOL.UTILICONFPESO) —
     * exigência do cliente Negri. Ver wms.backend.produtos.RegraPesavel.
     */
    const val PESAVEL_POR_PRODUTO = "pesavel_por_produto"

    /** Todos os módulos conhecidos — usado pela tela de admin de tenant pra listar as opções. */
    val TODOS = setOf(CONFERENCIA_SEGMENTADA, PESAVEL_POR_PRODUTO)
}
