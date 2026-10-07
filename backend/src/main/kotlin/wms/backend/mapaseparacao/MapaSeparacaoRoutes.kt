package wms.backend.mapaseparacao

import io.ktor.http.*
import io.ktor.server.request.*
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

        /** Registra a impressão de mapas (selo IMPRESSO + filtro Impressos / Não impressos). */
        post("/impressoes") {
            val claims = call.exigirAuth() ?: return@post
            val req = call.receive<RegistrarImpressaoRequest>()
            val nome = wms.backend.usuarios.UsuariosRepository.buscarPorId(claims.tenantId, claims.userId)?.nome
            val pedidosPorOc = req.pedidosPorOc.mapNotNull { (oc, pedidos) -> oc.toLongOrNull()?.let { it to pedidos } }.toMap()
            MapaImpressoesRepository.registrar(claims.tenantId, req.ordensCarga, req.nunotas, nome, pedidosPorOc)
            call.respond(mapOf("ok" to true))
        }

        /** Pedido de Venda (porte do Jasper) de cada Nº Único — impresso junto do mapa, um por pedido. */
        get("/pedidos-venda") {
            val claims = call.exigirAuth() ?: return@get
            val nunotas = call.request.queryParameters["nunotas"].orEmpty().split(",").mapNotNull { it.trim().toLongOrNull() }.distinct().take(100)
            if (nunotas.isEmpty()) return@get call.respond(emptyList<PedidoVendaDto>())
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            call.respond(PedidoVendaService.buscar(slug, nunotas))
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
                // ?nunotas=1,2 → mapa da OC só com esses pedidos ("Imprimir só os pedidos novos").
                val somente = call.request.queryParameters["nunotas"]?.split(",")?.mapNotNull { it.trim().toLongOrNull() }?.toSet()?.takeIf { it.isNotEmpty() }
                call.respond(MapaSeparacaoService.montar(slug, claims.tenantId, ordemCarga, somente))
            } catch (e: MapaSeparacaoService.MapaSeparacaoException) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to (e.message ?: "Ordem de Carga não encontrada")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao consultar o Sankhya")))
            }
        }
    }
}
