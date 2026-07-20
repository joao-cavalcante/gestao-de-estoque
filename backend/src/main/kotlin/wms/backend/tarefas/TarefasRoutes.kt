package wms.backend.tarefas

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
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

            call.respond(TarefasRepository.listar(tenantId))
        }

        post("/{nunota}/concluir") {
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
