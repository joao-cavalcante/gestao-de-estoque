package wms.backend.auditoria

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.exigirAuth
import wms.backend.tenancy.TenantRepository

/** Auditoria de Pedidos — JWT-auth (tenant vem do claim), só leitura, aberta a qualquer usuário logado. */
fun Route.auditoriaPedidoRoutes() {
    route("/api/auditoria-pedido") {
        /** {numero} = número único (NUNOTA) ou, se não achar, o número do pedido (NUMNOTA). */
        get("/{numero}") {
            val claims = call.exigirAuth() ?: return@get
            val numero = call.parameters["numero"]?.filter { it.isDigit() }?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "informe o número único ou o número do pedido"))
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            try {
                call.respond(AuditoriaPedidoService.auditar(slug, claims.tenantId, numero))
            } catch (e: AuditoriaPedidoService.PedidoNaoEncontradoException) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to e.message))
            } catch (e: Exception) {
                println("AVISO: auditoria do pedido $numero falhou: ${e.message}")
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to "falha ao consultar o Sankhya: ${e.message}"))
            }
        }
    }
}
