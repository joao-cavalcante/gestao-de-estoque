package wms.backend.impressao

import io.ktor.http.ContentType
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import wms.backend.auth.ClaimsToken
import wms.backend.auth.exigirAuth
import wms.backend.usuarios.UsuariosRepository
import java.util.UUID

@Serializable
data class ResultadoImpressaoRequest(val ok: Boolean, val mensagem: String? = null)

/** Contas que o agente local pode usar pra buscar impressões (conta de estação ou administrador). */
private val PERFIS_AGENTE = setOf("ESTACAO", "ADMINISTRADOR")

fun Route.impressaoOcRoutes() {
    /** Agente local (o mesmo da balança) — busca o próximo PDF, baixa e devolve o resultado. */
    route("/api/impressao/agente") {
        get("/proximo") {
            val claims = call.exigirAuth() ?: return@get
            if (claims.perfil !in PERFIS_AGENTE) {
                return@get call.respond(HttpStatusCode.Forbidden, mapOf("erro" to "use uma conta de estação no agente de impressão"))
            }
            // Botão "Testar" do agente: só confere login/permissão e conta a fila, sem pegar job.
            if (call.request.queryParameters["teste"] != null) {
                return@get call.respond(mapOf("prontos" to ImpressaoOcRepository.contarProntos(claims.tenantId)))
            }
            val agente = call.request.queryParameters["agente"]?.take(100) ?: nomeUsuario(claims)
            AgentesImpressao.visto(claims.tenantId, agente)
            val job = ImpressaoOcRepository.proximoParaAgente(claims.tenantId, agente)
                ?: return@get call.respond(HttpStatusCode.NoContent)
            call.respond(job)
        }
        get("/{id}/pdf") {
            val claims = call.exigirAuth() ?: return@get
            if (claims.perfil !in PERFIS_AGENTE) return@get call.respond(HttpStatusCode.Forbidden, mapOf("erro" to "sem permissão"))
            responderPdf(call, claims)
        }
        post("/{id}/resultado") {
            val claims = call.exigirAuth() ?: return@post
            if (claims.perfil !in PERFIS_AGENTE) return@post call.respond(HttpStatusCode.Forbidden, mapOf("erro" to "sem permissão"))
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "job inválido"))
            val req = call.receive<ResultadoImpressaoRequest>()
            val ok = ImpressaoOcRepository.registrarResultado(claims.tenantId, id, req.ok, req.mensagem)
            if (!req.ok) println("AVISO: impressão job $id falhou no agente: ${req.mensagem}")
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.Conflict, mapOf("ok" to ok))
        }
    }

    /** Tela "Impressão de OCs" (computador da expedição): agentes online + jobs das últimas 24 h. */
    get("/api/impressao/painel") {
        val claims = call.exigirAuth() ?: return@get
        val horas = call.request.queryParameters["horas"]?.toLongOrNull()?.coerceIn(1, 168) ?: 24
        call.respond(PainelImpressaoDto(AgentesImpressao.listar(claims.tenantId), ImpressaoOcRepository.recentes(claims.tenantId, horas, 200)))
    }

    /** Tela (card da OC na Fila): situação, reimpressão e visualização do PDF. */
    route("/api/impressao/oc") {
        get {
            val claims = call.exigirAuth() ?: return@get
            val ocs = call.request.queryParameters["ocs"]?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
            call.respond(ImpressaoOcRepository.ultimosPorOc(claims.tenantId, ocs.take(500)).values.toList())
        }
        post("/{oc}/reimprimir") {
            val claims = call.exigirAuth() ?: return@post
            val oc = call.parameters["oc"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "OC inválida"))
            // ?job=<id>: reimprime o que aquele job imprime (ex.: só o DANFE da nota que saiu separada).
            val nunotas = call.request.queryParameters["job"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?.let { ImpressaoOcRepository.buscar(claims.tenantId, it) }
                ?.takeIf { it.ordemCarga == oc }?.nunotas
                ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }
            ImpressaoOcRepository.enfileirar(claims.tenantId, oc, "reimpressao", nomeUsuario(claims), nunotas)
            call.respond(ImpressaoOcRepository.ultimosPorOc(claims.tenantId, listOf(oc)).getValue(oc))
        }
        get("/job/{id}/pdf") {
            val claims = call.exigirAuth() ?: return@get
            responderPdf(call, claims)
        }
    }
}

private suspend fun responderPdf(call: ApplicationCall, claims: ClaimsToken) {
    val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: return call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "job inválido"))
    val pdf = ImpressaoOcRepository.pdf(claims.tenantId, id)
        ?: return call.respond(HttpStatusCode.NotFound, mapOf("erro" to "PDF ainda não gerado"))
    call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Inline.withParameter(ContentDisposition.Parameters.FileName, "impressao-$id.pdf").toString())
    call.respondBytes(pdf, ContentType.Application.Pdf)
}

private fun nomeUsuario(claims: ClaimsToken): String =
    runCatching { UsuariosRepository.buscarPorId(claims.tenantId, claims.userId)?.nome }.getOrNull() ?: claims.userId.toString()
