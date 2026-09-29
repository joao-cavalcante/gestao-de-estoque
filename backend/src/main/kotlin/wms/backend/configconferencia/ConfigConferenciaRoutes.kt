package wms.backend.configconferencia

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.exigirAdmin
import wms.backend.auth.exigirAuth
import wms.backend.tenancy.TenantRepository

/** Mesmo padrão de tenant via JWT já usado em transferenciaRoutes()/inventarioRoutes(). */
fun Route.configConferenciaRoutes() {
    route("/api/config-conferencia") {

        get {
            val claims = call.exigirAuth() ?: return@get
            call.respond(ConfigConferenciaRepository.listar(claims.tenantId))
        }

        get("/{nucco}") {
            val claims = call.exigirAuth() ?: return@get
            val nucco = call.parameters["nucco"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'nucco' precisa ser um número"))

            val detalhe = ConfigConferenciaRepository.buscarPorNucco(claims.tenantId, nucco)
            if (detalhe == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Configuração de Conferência não encontrada"))
                return@get
            }
            call.respond(detalhe)
        }

        patch("/{nucco}") {
            val claims = call.exigirAuth() ?: return@patch
            val nucco = call.parameters["nucco"]?.toIntOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'nucco' precisa ser um número"))
            val body = call.receive<AtualizarConfigConferenciaRequest>()

            val ok = ConfigConferenciaRepository.atualizarLocal(claims.tenantId, nucco, body.campos)
            if (!ok) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Configuração de Conferência não encontrada"))
                return@patch
            }
            call.respond(ConfigConferenciaRepository.buscarPorNucco(claims.tenantId, nucco)!!)
        }

        /** V50 — tolerância de peso do WMS pra este NUCCO (sem linha = padrão: acima sem limite, abaixo 5%). */
        get("/{nucco}/tolerancia-peso") {
            val claims = call.exigirAuth() ?: return@get
            val nucco = call.parameters["nucco"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'nucco' precisa ser um número"))
            call.respond(ConferenciaToleranciaRepository.buscar(claims.tenantId, nucco))
        }

        put("/{nucco}/tolerancia-peso") {
            val claims = call.exigirAdmin() ?: return@put
            val nucco = call.parameters["nucco"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'nucco' precisa ser um número"))
            val body = call.receive<ToleranciaPesoDto>()
            val invalido = listOfNotNull(body.acimaPct, body.abaixoPct).any { it < 0 || it > 1000 }
            if (invalido) {
                return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "tolerância deve ficar entre 0 e 1000%"))
            }
            ConferenciaToleranciaRepository.salvar(claims.tenantId, nucco, body.acimaPct, body.abaixoPct)
            call.respond(ConferenciaToleranciaRepository.buscar(claims.tenantId, nucco))
        }

        post("/sincronizar") {
            val claims = call.exigirAuth() ?: return@post
            val tenant = TenantRepository.buscarPorId(claims.tenantId)
            if (tenant == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Tenant não encontrado"))
                return@post
            }

            try {
                val total = ConfigConferenciaSyncService.sincronizarTenant(tenant.slug, claims.tenantId)
                call.respond(SincronizarResponse(ok = true, totalSincronizado = total))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "Falha ao sincronizar com o Sankhya")))
            }
        }
    }
}
