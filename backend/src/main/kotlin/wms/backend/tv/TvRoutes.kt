package wms.backend.tv

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.exigirAuth
import wms.backend.auth.podeVerTv

/** TV de acompanhamento da conferência — permissão TV_CONFERENCIA (perfil TV ou ADMINISTRADOR, ver podeVerTv). */
fun Route.tvRoutes() {
    route("/api/tv") {
        get("/resumo") {
            val claims = call.exigirAuth() ?: return@get
            if (!podeVerTv(claims)) {
                return@get call.respond(HttpStatusCode.Forbidden, mapOf("erro" to "sem permissão para a TV de conferência"))
            }
            // ?movimento=saida (vendas) | entrada (compras) | ausente = todos
            call.respond(TvService.resumo(claims.tenantId, call.request.queryParameters["movimento"]))
        }
        /** TV exclusiva de Ordens de Carga (/tv/carga). */
        get("/carga") {
            val claims = call.exigirAuth() ?: return@get
            if (!podeVerTv(claims)) {
                return@get call.respond(HttpStatusCode.Forbidden, mapOf("erro" to "sem permissão para a TV de conferência"))
            }
            val slug = wms.backend.tenancy.TenantRepository.buscarPorId(claims.tenantId)?.slug
            call.respond(TvService.carga(claims.tenantId, slug))
        }
    }
}
