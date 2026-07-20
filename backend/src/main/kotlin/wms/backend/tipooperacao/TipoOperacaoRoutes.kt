package wms.backend.tipooperacao

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.exigirAuth

/** Mesmo padrão de tenant via JWT já usado em configConferenciaRoutes(). */
fun Route.tipoOperacaoRoutes() {
    route("/api/tipos-operacao") {

        get {
            val claims = call.exigirAuth() ?: return@get
            call.respond(TipoOperacaoRepository.listar(claims.tenantId))
        }

        post("/sincronizar") {
            val claims = call.exigirAuth() ?: return@post
            try {
                val total = TipoOperacaoSyncService.sincronizarTenant(claims.tenantId)
                call.respond(SincronizarTipoOperacaoResponse(ok = true, totalAtualizado = total))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("erro" to (e.message ?: "Falha ao sincronizar")))
            }
        }
    }
}
