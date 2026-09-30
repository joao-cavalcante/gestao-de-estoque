package wms.backend.tipooperacao

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.exigirAdmin
import wms.backend.auth.exigirAuth

/** Mesmo padrão de tenant via JWT já usado em configConferenciaRoutes(). */
fun Route.tipoOperacaoRoutes() {
    route("/api/tipos-operacao") {

        get {
            val claims = call.exigirAuth() ?: return@get
            call.respond(TipoOperacaoRepository.listar(claims.tenantId))
        }

        /** Liga/desliga a conferência por etapa pras notas deste TOP (ex.: entrada/compra não usa). */
        put("/{codtop}/config") {
            val claims = call.exigirAdmin() ?: return@put
            val codtop = call.parameters["codtop"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "codtop precisa ser um número"))
            val body = call.receive<TipoOperacaoConfigRequest>()
            TipoOperacaoRepository.definirConferenciaPorEtapa(claims.tenantId, codtop, body.conferenciaPorEtapa)
            call.respond(mapOf("ok" to true))
        }

        post("/sincronizar") {
            val claims = call.exigirAuth() ?: return@post
            val tenant = wms.backend.tenancy.TenantRepository.buscarPorId(claims.tenantId)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Tenant não encontrado"))
            try {
                val total = TipoOperacaoSyncService.sincronizarTenant(tenant.slug, claims.tenantId)
                call.respond(SincronizarTipoOperacaoResponse(ok = true, totalAtualizado = total))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("erro" to (e.message ?: "Falha ao sincronizar")))
            }
        }
    }
}
