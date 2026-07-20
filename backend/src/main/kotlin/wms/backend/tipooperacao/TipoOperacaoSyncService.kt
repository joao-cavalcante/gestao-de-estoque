package wms.backend.tipooperacao

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import wms.backend.tarefas.TarefasTable
import wms.backend.tenancy.TenantRepository
import wms.backend.tenancy.TenantTx
import java.util.UUID

/**
 * NÃO consulta o Sankhya diretamente — deriva o espelho local de Tipos de
 * Operação a partir do que TarefaSyncService já sincroniza em app.tarefas
 * (CODTIPOPER, TipoOperacao.DESCROPER, TipoOperacao.NUCCO fazem parte do
 * FIELDS de lá desde que este módulo passou a existir).
 *
 * Motivo: a entidade "TipoOperacao" isolada (via CRUDServiceProvider, único
 * jeito de consultá-la fora de um relacionamento) é comprovadamente
 * INCOMPLETA — confirmado ao vivo que uma varredura paginada inteira nela
 * nunca lista CODTIPOPER 1011 (CUBAGEM DE PEDIDO), apesar de ser um TOP real,
 * ativo, corretamente vinculado a NUCCO=1 quando resolvido via
 * CabecalhoNota->TipoOperacao.NUCCO — o mesmo caminho que a Fila de Tarefas
 * já usa e comprovadamente funciona. Como consequência, este módulo também
 * não tem mais um DHALTER próprio pra sincronização incremental (V17): a
 * "atualização" dele é instantânea porque só lê dados locais já frescos,
 * sem chamada de rede.
 */
object TipoOperacaoSyncService {

    fun sincronizarTenant(tenantId: UUID): Int {
        val derivados = TenantTx.run(tenantId) {
            TarefasTable.selectAll()
                .where { TarefasTable.tenantId eq tenantId }
                .mapNotNull { row ->
                    val dados = Json.parseToJsonElement(row[TarefasTable.dados]).jsonObject
                    val codtop = dados["CODTIPOPER"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
                    val nucco = dados["TipoOperacao.NUCCO"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    val descricao = dados["TipoOperacao.DESCROPER"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: "Tipo de Operação $codtop"
                    TopDerivado(codtop, descricao, nucco)
                }
                .distinctBy { it.codtop }
        }
        return TipoOperacaoRepository.substituirDerivado(tenantId, derivados)
    }

    /** Chamado pelo worker periódico. */
    fun sincronizarTodosOsTenants() {
        TenantRepository.listar().forEach { tenant ->
            val tenantId = tenant.id?.let { UUID.fromString(it) } ?: return@forEach
            try {
                sincronizarTenant(tenantId)
            } catch (e: Exception) {
                // próximo ciclo tenta de novo
            }
        }
    }
}
