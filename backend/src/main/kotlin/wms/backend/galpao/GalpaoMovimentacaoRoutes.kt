package wms.backend.galpao

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Movimentação por endereço (fase 4) — por enquanto só ADMINISTRADOR (decisão do usuário, 03/11/2026),
 * inclusive consultas de histórico, contagens e conciliação. Montado dentro de route("/api/galpao").
 */
fun Route.galpaoMovimentacaoRoutes() {

    post("/plantas/{id}/movimentos") {
        val claims = call.exigirGalpao(admin = true) ?: return@post
        val id = call.uuid("id") ?: return@post call.respond(HttpStatusCode.BadRequest)
        val body = call.receive<MovimentoRequest>()
        call.responderMovimentacao { GalpaoMovimentacao.executar(claims.tenantId, id, body, claims.userId, nomeUsuario(claims)) }
    }

    get("/plantas/{id}/movimentos") {
        val claims = call.exigirGalpao(admin = true) ?: return@get
        val id = call.uuid("id") ?: return@get call.respond(HttpStatusCode.BadRequest)
        val q = call.request.queryParameters
        call.respond(
            GalpaoMovimentacao.listarMovimentos(
                claims.tenantId, id, q["codprod"]?.toIntOrNull(), q["endereco"], q["tipo"], q["pagina"]?.toIntOrNull() ?: 1,
            ),
        )
    }

    get("/plantas/{id}/contagens") {
        val claims = call.exigirGalpao(admin = true) ?: return@get
        val id = call.uuid("id") ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.respond(GalpaoMovimentacao.listarContagens(claims.tenantId, id, call.request.queryParameters["status"]))
    }

    post("/plantas/{id}/contagens/{contagemId}/aprovar") {
        val claims = call.exigirGalpao(admin = true) ?: return@post
        val id = call.uuid("id") ?: return@post call.respond(HttpStatusCode.BadRequest)
        val contagemId = call.uuid("contagemId") ?: return@post call.respond(HttpStatusCode.BadRequest)
        val body = call.receive<DecisaoContagemRequest>()
        call.responderMovimentacao { GalpaoMovimentacao.aprovarContagem(claims.tenantId, id, contagemId, body.observacao, claims.userId, nomeUsuario(claims)) }
    }

    post("/plantas/{id}/contagens/{contagemId}/descartar") {
        val claims = call.exigirGalpao(admin = true) ?: return@post
        val id = call.uuid("id") ?: return@post call.respond(HttpStatusCode.BadRequest)
        val contagemId = call.uuid("contagemId") ?: return@post call.respond(HttpStatusCode.BadRequest)
        val body = call.receive<DecisaoContagemRequest>()
        call.responderMovimentacao { GalpaoMovimentacao.descartarContagem(claims.tenantId, id, contagemId, body.observacao, nomeUsuario(claims)) }
    }

    get("/plantas/{id}/produtos") {
        val claims = call.exigirGalpao(admin = true) ?: return@get
        call.respond(GalpaoMovimentacao.buscarProdutos(claims.tenantId, call.request.queryParameters["busca"].orEmpty()))
    }

    get("/plantas/{id}/estoque-produto/{codprod}") {
        val claims = call.exigirGalpao(admin = true) ?: return@get
        val id = call.uuid("id") ?: return@get call.respond(HttpStatusCode.BadRequest)
        val codprod = call.parameters["codprod"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.responderMovimentacao { GalpaoMovimentacao.estoqueProduto(claims.tenantId, id, codprod) }
    }

    get("/plantas/{id}/conciliacao") {
        val claims = call.exigirGalpao(admin = true) ?: return@get
        val id = call.uuid("id") ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.responderMovimentacao { GalpaoMovimentacao.conciliacao(claims.tenantId, id) }
    }
}

/** Erro de negócio (MovimentacaoException) vira {erro} com o status dele; o resto segue pro StatusPages. */
private suspend inline fun <reified T : Any> ApplicationCall.responderMovimentacao(bloco: () -> T) {
    try {
        respond(bloco())
    } catch (e: MovimentacaoException) {
        respond(e.status, mapOf("erro" to (e.message ?: "Operação recusada")))
    }
}
