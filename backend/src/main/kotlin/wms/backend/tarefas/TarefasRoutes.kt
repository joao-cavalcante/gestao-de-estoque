package wms.backend.tarefas

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.ClaimsToken
import wms.backend.auth.exigirAuth
import wms.backend.permissoes.PermissoesRecurso
import wms.backend.tenancy.TenantRepository
import java.util.UUID

/**
 * Único jeito do frontend enxergar tarefas — lê EXCLUSIVAMENTE a base
 * local (Postgres), nunca aciona o Sankhya na hora da requisição. Quem
 * mantém isso atualizado é o TarefaSyncScheduler, em background.
 */
fun Route.tarefasRoutes() {
    route("/api/tarefas") {

        get {
            val claims = call.exigirAuth() ?: return@get
            val slug = call.request.queryParameters["tenant"]
            if (slug.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "query param 'tenant' é obrigatório"))
                return@get
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@get
            }
            if (tenantId != claims.tenantId) {
                call.respond(HttpStatusCode.Forbidden, mapOf("erro" to "token não pertence a este tenant"))
                return@get
            }

            call.respond(comTransporte(slug, tenantId, filtrarPorTop(claims, tenantId, TarefasRepository.listar(tenantId))))
        }

        // Sincronização sob demanda ("forçar sync"): roda o mesmo ciclo do job de
        // background na hora e devolve a fila já atualizada. Útil quando o operador
        // mexeu na conferência direto no Sankhya e não quer esperar o ciclo de ~60s.
        post("/sincronizar") {
            val claims = call.exigirAuth() ?: return@post
            val slug = call.request.queryParameters["tenant"]
            if (slug.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "query param 'tenant' é obrigatório"))
                return@post
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@post
            }
            if (tenantId != claims.tenantId) {
                call.respond(HttpStatusCode.Forbidden, mapOf("erro" to "token não pertence a este tenant"))
                return@post
            }
            try {
                TarefaSyncService.sincronizarTenant(slug, tenantId)
                call.respond(comTransporte(slug, tenantId, filtrarPorTop(claims, tenantId, TarefasRepository.listar(tenantId))))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("erro" to "Falha ao sincronizar com o Sankhya: ${e.message ?: e::class.simpleName ?: "erro desconhecido"}"),
                )
            }
        }

        post("/{nunota}/concluir") {
            val claims = call.exigirAuth() ?: return@post
            val slug = call.request.queryParameters["tenant"]
            val nunota = call.parameters["nunota"]?.toLongOrNull()
            if (slug.isNullOrBlank() || nunota == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e nunota (path) são obrigatórios"))
                return@post
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@post
            }
            if (tenantId != claims.tenantId) {
                call.respond(HttpStatusCode.Forbidden, mapOf("erro" to "token não pertence a este tenant"))
                return@post
            }

            val body = call.receive<ConcluirTarefaRequest>()

            // Resposta rápida ao usuário: grava local e devolve na hora.
            val encontrada = TarefasRepository.concluirLocal(tenantId, nunota, body.operador)
            if (!encontrada) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tarefa nunota=$nunota não encontrada para o tenant '$slug'"))
                return@post
            }

            // Write-back real no Sankhya é assíncrono (fila com retry) —
            // não bloqueia a resposta.
            WriteBackQueue.enfileirar(slug, tenantId, nunota)

            call.respond(HttpStatusCode.Accepted, mapOf("ok" to true, "pendenteWriteBack" to true))
        }
    }
}

private fun resolverTenantId(slug: String): UUID? =
    TenantRepository.buscarPorSlug(slug)?.id?.let { UUID.fromString(it) }

/** Fila só com as notas cuja TOP a conta logada pode conferir (PermissoesRecurso; admin vê tudo). */
/** Motorista/veículo da OC nos cards — só do cache (ver TransporteOrdemCarga.doCache), a fila continua sem esperar o Sankhya. */
private fun comTransporte(slug: String, tenantId: UUID, tarefas: List<TarefaApiDto>): List<TarefaApiDto> {
    val ocs = tarefas.mapNotNull { it.ordemCarga }
    if (ocs.isEmpty()) return tarefas
    val transporte = wms.backend.mapaseparacao.TransporteOrdemCarga.doCache(slug, tenantId, ocs)
    return tarefas.map { t ->
        t.ordemCarga?.let { transporte[it] }?.let { t.copy(motorista = it.motorista, placa = it.placa, veiculo = it.veiculo) } ?: t
    }
}

private fun filtrarPorTop(claims: ClaimsToken, tenantId: UUID, tarefas: List<TarefaApiDto>): List<TarefaApiDto> {
    if (PermissoesRecurso.acessoTotal(claims)) return tarefas
    val restritas = PermissoesRecurso.topsRestritas(tenantId)
    if (restritas.isEmpty()) return tarefas
    return tarefas.filter { PermissoesRecurso.podeUsarTop(claims, restritas, it.codigoTipoOperacao?.trim()?.toIntOrNull()) }
}
