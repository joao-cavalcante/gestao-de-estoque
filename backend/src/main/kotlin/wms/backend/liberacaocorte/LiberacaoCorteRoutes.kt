package wms.backend.liberacaocorte

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.exigirAuth
import wms.backend.tenancy.TenantRepository

/**
 * Liberação de corte — tela de supervisor + endpoints usados também pelo
 * modal inline no fim da conferência. JWT-auth (tenant vem do claim); o slug
 * é resolvido só pras chamadas Sankhya.
 *
 * A autorização de verdade da MUTAÇÃO é o usuário/senha do liberador validado
 * no Sankhya (LiberacaoLimitesSP.validarSenhaUsuario) — igual ao legado.
 */
fun Route.liberacaoCorteRoutes() {
    route("/api/liberacao-corte") {

        /**
         * Conferências travadas em TGFCON2.STATUS='C'. Revalida cada uma contra
         * o Sankhya — as que já não estão mais em corte (ou estão presas sem
         * itens) são fechadas localmente e não aparecem.
         */
        get {
            val claims = call.exigirAuth() ?: return@get
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
            try {
                if (slug == null) {
                    call.respond(LiberacaoCorteRepository.listarAguardandoCorte(claims.tenantId))
                } else {
                    call.respond(LiberacaoCorteService.listarRevalidando(slug, claims.tenantId))
                }
            } catch (e: Exception) {
                // Falha ao revalidar (Sankhya fora) — devolve a lista local crua, sem travar a tela.
                call.respond(LiberacaoCorteRepository.listarAguardandoCorte(claims.tenantId))
            }
        }

        /** Itens pendentes de liberação de uma conferência. */
        get("/pendentes") {
            val claims = call.exigirAuth() ?: return@get
            val nuconf = call.request.queryParameters["nuconf"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'nuconf' precisa ser um número"))
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            try {
                call.respond(LiberacaoCorteService.listarPendentes(slug, nuconf))
            } catch (e: LiberacaoCorteService.LiberacaoCorteException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to (e.message ?: "falha")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao consultar o Sankhya")))
            }
        }

        /** Etapa 1 do modal: valida usuário/senha do liberador no Sankhya. */
        post("/validar-liberador") {
            val claims = call.exigirAuth() ?: return@post
            val body = call.receive<ValidarLiberadorRequest>()
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            try {
                LiberacaoCorteService.validarLiberador(slug, body.usuario, body.senha)
                call.respond(mapOf("ok" to true))
            } catch (e: LiberacaoCorteService.LiberacaoCorteException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to (e.message ?: "usuário ou senha inválidos")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao validar no Sankhya")))
            }
        }

        /** Etapa 2 do modal: libera ('S') ou nega ('N') os itens selecionados. */
        post("/liberar") {
            val claims = call.exigirAuth() ?: return@post
            val body = call.receive<LiberarCorteRequest>()
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            try {
                val n = LiberacaoCorteService.liberarOuNegar(
                    slug, claims.tenantId, body.nuconf, body.usuario, body.senha, body.liberar, body.sequencias, body.obs,
                )
                call.respond(LiberarCorteResponse(ok = true, itensProcessados = n))
            } catch (e: LiberacaoCorteService.LiberacaoCorteException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to (e.message ?: "falha na liberação")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao processar no Sankhya")))
            }
        }
    }
}
