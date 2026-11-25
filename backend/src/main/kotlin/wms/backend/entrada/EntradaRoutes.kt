package wms.backend.entrada

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.exigirAuth
import wms.backend.tenancy.TenantRepository

/**
 * Tela Entrada (recebimentos de compra) e mesa de liberação — leitura da base local; a situação da nota no Sankhya
 * vem do cache que o ciclo de sincronização mantém (EntradaSituacao). A única escrita no Sankhya é o "Aprovar" da mesa
 * (etapa 2, 23/11/2026), feito pelo addon TOR com o usuário e a senha do liberador.
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

        /** Aprovar o recebimento: o addon TOR confirma a compra em nome do liberador e registra em TORREC. */
        post("/{nunota}/aprovar") {
            val claims = call.exigirAuth() ?: return@post
            val nunota = call.parameters["nunota"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "nº único inválido"))
            // Só compra que a pessoa enxerga na Entrada (mesmo filtro de TOP/permissão da mesa).
            if (EntradaService.mesa(claims, nunota) == null) {
                return@post call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Recebimento $nunota não encontrado na Entrada"))
            }
            val body = call.receive<AprovarRecebimentoRequest>()
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            try {
                call.respond(EntradaAprovacao.aprovar(slug, claims.tenantId, nunota, body.usuario.trim(), body.senha))
            } catch (e: EntradaAprovacao.AprovacaoException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to (e.message ?: "não foi possível aprovar")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao aprovar no Sankhya")))
            }
        }
    }
}
