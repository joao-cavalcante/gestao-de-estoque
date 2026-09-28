package wms.backend.mapaseparacao

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.exigirAuth
import wms.backend.tenancy.TenantRepository

/**
 * Mapa de Separação por Ordem de Carga — porte do Dashboard HTML5 do
 * Sankhya (ver MapaSeparacaoService). JWT-auth (tenant vem do claim); o
 * slug só é resolvido pra chamada ao Sankhya, igual a LiberacaoCorteRoutes.
 */
fun Route.mapaSeparacaoRoutes() {
    route("/api/mapa-separacao") {

        /** Ordens de Carga abertas (TGFORD.SITUACAO='A') — ainda precisam ser separadas; oferece lista em vez de digitar de cor. */
        get("/abertas") {
            val claims = call.exigirAuth() ?: return@get
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            try {
                call.respond(MapaSeparacaoService.listarAbertas(slug, claims.tenantId))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao consultar o Sankhya")))
            }
        }

        /** Pedidos SEM Ordem de Carga (mirror local) — painel do filtro "S/ Ordem de Carga". */
        get("/sem-ordem-carga") {
            val claims = call.exigirAuth() ?: return@get
            call.respond(MapaSeparacaoService.listarSemOrdemCarga(claims.tenantId))
        }

        /** Mapa S/ Ordem de Carga de UM pedido — um mapa por Número Único, nunca consolidado com outro. */
        get("/sem-ordem-carga/{nunota}") {
            val claims = call.exigirAuth() ?: return@get
            val nunota = call.parameters["nunota"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "Número Único precisa ser um número"))
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            try {
                call.respond(MapaSeparacaoService.montarSemOrdemCarga(slug, claims.tenantId, nunota))
            } catch (e: MapaSeparacaoService.MapaSeparacaoException) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to (e.message ?: "Pedido não encontrado")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao consultar o Sankhya")))
            }
        }

        get("/{ordemCarga}") {
            val claims = call.exigirAuth() ?: return@get
            val ordemCarga = call.parameters["ordemCarga"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "Ordem de Carga precisa ser um número"))
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))

            try {
                call.respond(MapaSeparacaoService.montar(slug, claims.tenantId, ordemCarga))
            } catch (e: MapaSeparacaoService.MapaSeparacaoException) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to (e.message ?: "Ordem de Carga não encontrada")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao consultar o Sankhya")))
            }
        }
    }
}
