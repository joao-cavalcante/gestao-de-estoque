package wms.backend.configconferencia

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.exigirAdmin
import wms.backend.auth.exigirAuth
import wms.backend.tenancy.ParametrosAjustePesoCompra
import wms.backend.tenancy.TenantRepository

/** Mesmo padrão de tenant via JWT já usado em transferenciaRoutes()/inventarioRoutes(). */
fun Route.configConferenciaRoutes() {
    route("/api/config-conferencia") {

        get {
            val claims = call.exigirAdmin() ?: return@get
            call.respond(ConfigConferenciaRepository.listar(claims.tenantId))
        }

        get("/{nucco}") {
            val claims = call.exigirAdmin() ?: return@get
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
            val claims = call.exigirAdmin() ?: return@patch
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
            val claims = call.exigirAdmin() ?: return@get
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

        /** V70 — opções da Torre pra este NUCCO (ex.: pedir quantidade na unidade do pedido na pesagem). */
        get("/{nucco}/opcoes") {
            val claims = call.exigirAdmin() ?: return@get
            val nucco = call.parameters["nucco"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'nucco' precisa ser um número"))
            call.respond(ConferenciaOpcoesRepository.buscar(claims.tenantId, nucco))
        }

        put("/{nucco}/opcoes") {
            val claims = call.exigirAdmin() ?: return@put
            val nucco = call.parameters["nucco"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'nucco' precisa ser um número"))
            ConferenciaOpcoesRepository.salvar(claims.tenantId, nucco, call.receive<OpcoesConferenciaDto>())
            call.respond(ConferenciaOpcoesRepository.buscar(claims.tenantId, nucco))
        }

        /**
         * Diferença de peso na COMPRA (faixas 2 ajuste / 3 divergência — AjustePesoCompra), configuração do cliente
         * inteiro (não é por NUCCO). Mesmo parâmetro do Painel Master, também editável aqui pelo ADMINISTRADOR do
         * tenant (usuário, 10/11/2026: "trazer nossas novas configurações para a aba corte/divergência").
         */
        get("/ajuste-peso-compra") {
            val claims = call.exigirAdmin() ?: return@get
            call.respond(TenantRepository.parametros(claims.tenantId).ajustePesoCompra)
        }

        put("/ajuste-peso-compra") {
            val claims = call.exigirAdmin() ?: return@put
            val novo = try {
                call.receive<ParametrosAjustePesoCompra>()
            } catch (e: Exception) {
                return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "configuração inválida: ${e.message}"))
            }
            val erros = novo.validar()
            if (erros.isNotEmpty()) return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to erros.joinToString("; ")))
            val atual = TenantRepository.parametros(claims.tenantId)
            if (!TenantRepository.salvarParametros(claims.tenantId, atual.copy(ajustePesoCompra = novo))) {
                return@put call.respond(HttpStatusCode.Conflict, mapOf("erro" to "cliente sem integração Sankhya cadastrada"))
            }
            println("INFO: ajustePesoCompra alterado pelo usuário ${claims.userId} (tenant ${claims.tenantId}): ${atual.ajustePesoCompra} -> $novo")
            call.respond(TenantRepository.parametros(claims.tenantId).ajustePesoCompra)
        }

        post("/sincronizar") {
            val claims = call.exigirAdmin() ?: return@post
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
