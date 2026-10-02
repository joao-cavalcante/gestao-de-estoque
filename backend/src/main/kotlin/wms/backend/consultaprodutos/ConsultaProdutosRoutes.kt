package wms.backend.consultaprodutos

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.exigirAuth
import wms.backend.tenancy.TenantRepository

/** Consulta de Produtos — JWT-auth (tenant vem do claim), aberta a qualquer usuário logado. */
fun Route.consultaProdutosRoutes() {
    route("/api/consulta-produtos") {
        /** ?q= código, código de barras, descrição, marca ou referência. */
        get {
            val claims = call.exigirAuth() ?: return@get
            val termo = call.request.queryParameters["q"]?.trim().orEmpty()
            if (termo.length < 2) {
                return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "digite pelo menos 2 caracteres"))
            }
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            call.respond(ConsultaProdutosService.consultar(slug, claims.tenantId, termo))
        }
    }
}
