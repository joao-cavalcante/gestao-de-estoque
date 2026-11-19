package wms.backend.entrada

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.exigirAuth

/**
 * Tela Entrada (recebimentos de compra) e mesa de liberação — leitura da base local; a situação da nota no Sankhya
 * vem do cache que o ciclo de sincronização mantém (EntradaSituacao). Nenhuma escrita no Sankhya.
 */
fun Route.entradaRoutes() {
    route("/api/entrada") {
        get {
            val claims = call.exigirAuth() ?: return@get
            call.respond(EntradaService.listar(claims))
        }

        get("/{nunota}/mesa") {
            val claims = call.exigirAuth() ?: return@get
            val nunota = call.parameters["nunota"]?.toLongOrNull()
            if (nunota == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "nº único inválido"))
                return@get
            }
            val mesa = EntradaService.mesa(claims, nunota)
            if (mesa == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Recebimento $nunota não encontrado na Entrada"))
                return@get
            }
            call.respond(mesa)
        }
    }
}
