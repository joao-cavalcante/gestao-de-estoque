package wms.backend.tenancy

/**
 * Registro central dos nomes de MÓDULO por-tenant — os valores válidos pra
 * `tenancy.erp_connections.modulos text[]` (feature flags habilitadas pro
 * tenant, ver V1__tenancy_foundation.sql).
 *
 * Módulo != configuração de conferência (TGFCCO, vinda do Sankhya): módulo é
 * conceito do WMS, ligado/desligado só pela plataforma (master), e serve pra
 * isolar comportamento que só um cliente usa. Ver TenantRepository.modulosHabilitados.
 * Ligar/desligar: só o Painel Master (PUT /api/master/tenants/{id}/modulos), com confirmação e auditoria.
 */
object Modulos {
    /** Conferência segmentada — conceito exclusivo de um cliente; nenhum outro tenant enxerga. */
    const val CONFERENCIA_SEGMENTADA = "conferencia_segmentada"

    /**
     * Pesável pelo PRODUTO (TGFPRO.AD_PESAVEL = 'S') em vez da unidade (TGFVOL.UTILICONFPESO) —
     * exigência do cliente Negri. Ver wms.backend.produtos.RegraPesavel.
     */
    const val PESAVEL_POR_PRODUTO = "pesavel_por_produto"

    /**
     * DESLIGA a checagem do pedido com o Sankhya ao concluir etapa/finalizar (SeparacaoService.verificarPedidoAtualizado).
     * Ao contrário dos outros, é um "opt-out": sem o módulo a checagem RODA (padrão seguro, nota 61514);
     * marcar o módulo troca a segurança por desempenho (uma leitura a menos do pedido no Sankhya por conclusão).
     */
    const val SEM_VERIFICACAO_PEDIDO = "sem_verificacao_pedido"

    /**
     * Liberação herdada na confirmação da nota (wms.backend.separacao.LiberacaoHerdada): evento de limite já
     * liberado no pedido é liberado na nota pelo liberador de serviço. Nasceu na Negri (evento Java
     * negri-liberacao-herdada). Desligado = a regra nem consulta o Sankhya. Parâmetros em ParametrosTenant.liberacaoHerdada.
     */
    const val LIBERACAO_HERDADA = "liberacao_herdada"

    /** Todos os módulos conhecidos — usado pelo Painel Master pra listar as opções. */
    val TODOS = setOf(CONFERENCIA_SEGMENTADA, PESAVEL_POR_PRODUTO, SEM_VERIFICACAO_PEDIDO, LIBERACAO_HERDADA)

    /** Texto do Painel Master pra cada módulo. */
    fun descricao(modulo: String): String = when (modulo) {
        CONFERENCIA_SEGMENTADA -> "Conferência em etapas (Não pesáveis / Pesáveis) por TOP"
        PESAVEL_POR_PRODUTO -> "Pesável pelo produto (TGFPRO.AD_PESAVEL), não pela unidade"
        SEM_VERIFICACAO_PEDIDO -> "Não conferir o pedido com o Sankhya ao concluir etapa/finalizar (mais rápido, menos seguro)"
        LIBERACAO_HERDADA -> "Liberação herdada: evento de limite já liberado no pedido é liberado na nota (eventos nos parâmetros)"
        else -> modulo
    }
}
