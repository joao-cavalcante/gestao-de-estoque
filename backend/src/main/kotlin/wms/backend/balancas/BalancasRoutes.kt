package wms.backend.balancas

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.exigirAdmin
import wms.backend.auth.ClaimsToken
import wms.backend.auth.exigirAuth
import wms.backend.permissoes.PermissoesRecurso
import wms.backend.separacao.SeparacaoRepository
import java.util.UUID

fun Route.balancasRoutes() {
    route("/api/balancas") {

        get {
            val claims = call.exigirAuth() ?: return@get
            call.respond(BalancasRepository.listar(claims.tenantId))
        }

        get("/ativas") {
            val claims = call.exigirAuth() ?: return@get
            call.respond(BalancasRepository.listarAtivas(claims.tenantId))
        }

        /**
         * Balanças que o usuário pode usar na conferência (PermissoesRecurso). `?sessao=` = sessão de
         * conferência aberta: em conta de estação, vale o operador que bipou o crachá nela.
         */
        get("/minhas") {
            val claims = call.exigirAuth() ?: return@get
            call.respond(BalancasRepository.listarParaUsuario(claims.tenantId, usuarioDaBalanca(call, claims)))
        }

        /** Usuários autorizados da balança (admin). Lista vazia = sem restrição. */
        get("/{id}/usuarios") {
            val claims = call.exigirAdmin() ?: return@get
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "id inválido"))
            call.respond(UsuariosAutorizadosDto(BalancasRepository.listarUsuarios(claims.tenantId, id)))
        }

        put("/{id}/usuarios") {
            val claims = call.exigirAdmin() ?: return@put
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "id inválido"))
            BalancasRepository.buscarPorId(claims.tenantId, id)
                ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("erro" to "balança não encontrada"))
            val req = call.receive<UsuariosAutorizadosDto>()
            val ids = req.usuarioIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            BalancasRepository.definirUsuarios(claims.tenantId, id, ids.distinct())
            call.respond(UsuariosAutorizadosDto(ids.distinct().map { it.toString() }))
        }

        post {
            val claims = call.exigirAdmin() ?: return@post
            val req = call.receive<SalvarBalancaRequest>()
            call.respond(HttpStatusCode.Created, BalancasRepository.criar(claims.tenantId, req))
        }

        patch("/{id}") {
            val claims = call.exigirAdmin() ?: return@patch
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "id inválido"))
            val req = call.receive<SalvarBalancaRequest>()
            if (!BalancasRepository.atualizar(claims.tenantId, id, req)) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "balança não encontrada"))
                return@patch
            }
            call.respond(mapOf("ok" to true))
        }

        get("/{id}/capturar-peso") {
            val claims = call.exigirAuth() ?: return@get
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "id inválido"))

            val balanca = BalancasRepository.buscarPorId(claims.tenantId, id)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "balança não encontrada"))
            // Teste de leitura na tela de Balanças (admin, sem sessão de conferência) não é uso na conferência.
            val testeDeConfiguracao = claims.perfil == "ADMINISTRADOR" && call.request.queryParameters["sessao"] == null
            if (!testeDeConfiguracao && !PermissoesRecurso.podeUsarBalanca(claims.tenantId, usuarioDaBalanca(call, claims), id)) {
                return@get call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("codigo" to "BALANCA_NAO_AUTORIZADA", "erro" to "Você não tem permissão para usar esta balança."),
                )
            }

            if (balanca.tipoComunicacao != "HTTP") {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "captura via servidor só é suportada para tipoComunicacao=HTTP (demais tipos são lidos pelo agente local, no navegador)"))
                return@get
            }

            try {
                val peso = BalancaHttpClient.lerPeso(balanca)
                call.respond(PesoCapturadoDto(peso))
            } catch (e: BalancaHttpException) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to e.message))
            }
        }

        delete("/{id}") {
            val claims = call.exigirAdmin() ?: return@delete
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "id inválido"))
            if (!BalancasRepository.remover(claims.tenantId, id)) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "balança não encontrada"))
                return@delete
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** Usuário efetivo p/ permissão de balança: o logado; em conta de estação, o operador (crachá) da `?sessao=`. */
private fun usuarioDaBalanca(call: io.ktor.server.application.ApplicationCall, claims: ClaimsToken): UUID? {
    val sessaoId = call.request.queryParameters["sessao"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    val operador = sessaoId?.let { SeparacaoRepository.buscarSessao(claims.tenantId, it)?.operadorId }
    return PermissoesRecurso.usuarioEfetivo(claims, operador)
}
