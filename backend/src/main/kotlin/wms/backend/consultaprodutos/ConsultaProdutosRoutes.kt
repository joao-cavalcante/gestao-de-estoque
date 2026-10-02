package wms.backend.consultaprodutos

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.exigirAuth
import wms.backend.tenancy.TenantRepository

/** Consulta de Produtos — JWT-auth (tenant vem do claim), aberta a qualquer usuário logado. */
fun Route.consultaProdutosRoutes() {
    route("/api/consulta-produtos") {
        /** Catálogo inteiro com saldo. ?atualizar=true ignora a leitura de estoque em memória. */
        get {
            val claims = call.exigirAuth() ?: return@get
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            val forcar = call.request.queryParameters["atualizar"] == "true"
            call.respond(ConsultaProdutosService.consultar(slug, claims.tenantId, forcar))
        }
    }
}
