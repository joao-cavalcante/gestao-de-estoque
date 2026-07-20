package wms.backend.balancas

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.exigirAdmin
import wms.backend.auth.exigirAuth
import java.util.UUID

fun Route.balancasRoutes() {
    route("/api/balancas") {

        get {
            val claims = call.exigirAuth() ?: return@get
            call.respond(BalancasRepository.listar(claims.tenantId))
        }

        get("/ativas") {
            val claims = call.exigirAuth() ?: return@get
            call.respond(BalancasRepository.listarAtivas(claims.tenantId))
        }

        post {
            val claims = call.exigirAdmin() ?: return@post
            val req = call.receive<SalvarBalancaRequest>()
            call.respond(HttpStatusCode.Created, BalancasRepository.criar(claims.tenantId, req))
        }

        patch("/{id}") {
            val claims = call.exigirAdmin() ?: return@patch
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "id inválido"))
            val req = call.receive<SalvarBalancaRequest>()
            if (!BalancasRepository.atualizar(claims.tenantId, id, req)) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "balança não encontrada"))
                return@patch
            }
            call.respond(mapOf("ok" to true))
        }

        get("/{id}/capturar-peso") {
            val claims = call.exigirAuth() ?: return@get
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "id inválido"))

            val balanca = BalancasRepository.buscarPorId(claims.tenantId, id)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "balança não encontrada"))

            if (balanca.tipoComunicacao != "HTTP") {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "captura via servidor só é suportada para tipoComunicacao=HTTP (demais tipos são lidos pelo agente local, no navegador)"))
                return@get
            }

            try {
                val peso = BalancaHttpClient.lerPeso(balanca)
                call.respond(PesoCapturadoDto(peso))
            } catch (e: BalancaHttpException) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to e.message))
            }
        }

        delete("/{id}") {
            val claims = call.exigirAdmin() ?: return@delete
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "id inválido"))
            if (!BalancasRepository.remover(claims.tenantId, id)) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "balança não encontrada"))
                return@delete
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
