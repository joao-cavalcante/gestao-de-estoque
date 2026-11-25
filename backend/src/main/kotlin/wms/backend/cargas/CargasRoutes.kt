package wms.backend.cargas

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.exigirAuth
import wms.backend.tenancy.TenantRepository

/** Tela Cargas (24/11/2026) — só leitura. OCs abertas e as que saíram nas últimas 36 h (a tela recorta dia/turno). */
fun Route.cargasRoutes() {
    get("/api/cargas") {
        val claims = call.exigirAuth() ?: return@get
        val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
        call.respond(CargasService.painel(claims, claims.tenantId, slug))
    }
}
