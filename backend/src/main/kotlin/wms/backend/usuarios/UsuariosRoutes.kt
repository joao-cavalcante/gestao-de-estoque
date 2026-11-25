package wms.backend.usuarios

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.exigirAdmin
import wms.backend.auth.exigirAuth
import java.util.UUID

fun Route.usuariosRoutes() {
    route("/api/usuarios") {

        get {
            val claims = call.exigirAdmin() ?: return@get
            call.respond(UsuariosRepository.listar(claims.tenantId))
        }

        /** Empresas do Sankhya (TSIEMP) para o seletor "Empresas" do usuário (V80). */
        get("/empresas") {
            val claims = call.exigirAdmin() ?: return@get
            val slug = wms.backend.tenancy.TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            try {
                val linhas = wms.backend.erp.SankhyaDbExplorerClient.executarQuery(slug, "SELECT CODEMP, NOMEFANTASIA FROM TSIEMP ORDER BY CODEMP")
                call.respond(linhas.mapNotNull { l ->
                    val cod = wms.backend.permissoes.EmpresasAcesso.codemp(l["CODEMP"]) ?: return@mapNotNull null
                    EmpresaDto(cod, l["NOMEFANTASIA"]?.trim()?.takeIf { it.isNotEmpty() } ?: "Empresa $cod")
                })
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao ler as empresas no Sankhya")))
            }
        }

        post {
            val claims = call.exigirAdmin() ?: return@post
            val req = call.receive<CriarUsuarioRequest>()
            try {
                val criado = UsuariosRepository.criar(claims.tenantId, req)
                if (req.empresas.isNotEmpty()) {
                    wms.backend.permissoes.EmpresasAcesso.definir(claims.tenantId, UUID.fromString(criado.id), req.empresas)
                }
                call.respond(HttpStatusCode.Created, criado.copy(empresas = req.empresas.distinct().sorted()))
            } catch (e: EmailJaExisteException) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to e.message))
            }
        }

        patch("/{id}") {
            val claims = call.exigirAdmin() ?: return@patch
            val userId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "id inválido"))

            val req = call.receive<AtualizarUsuarioRequest>()
            val atualizado = UsuariosRepository.atualizar(claims.tenantId, userId, req)
            if (!atualizado) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "usuário não encontrado"))
                return@patch
            }
            req.empresas?.let { wms.backend.permissoes.EmpresasAcesso.definir(claims.tenantId, userId, it) }
            call.respond(mapOf("ok" to true))
        }

        delete("/{id}") {
            val claims = call.exigirAdmin() ?: return@delete
            val userId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "id inválido"))

            when (UsuariosRepository.remover(claims.tenantId, userId)) {
                UsuariosRepository.ResultadoRemocao.NAO_ENCONTRADO ->
                    call.respond(HttpStatusCode.NotFound, mapOf("erro" to "usuário não encontrado"))
                UsuariosRepository.ResultadoRemocao.DESATIVADO ->
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "desativado" to "true",
                            "mensagem" to "O usuário tem conferências no histórico, por isso foi desativado em vez de excluído.",
                        ),
                    )
                UsuariosRepository.ResultadoRemocao.EXCLUIDO -> call.respond(HttpStatusCode.NoContent)
            }
        }

        /**
         * "Criar no Sankhya": cria o usuário na TSIUSU pela entidade Usuario (modelo e grupo do tenant, V66) e
         * grava o CODUSU no vínculo (codigo_erp). Nome que já existe no Sankhya = só vincula.
         */
        post("/{id}/criar-no-sankhya") {
            val claims = call.exigirAdmin() ?: return@post
            val userId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "id inválido"))
            val usuario = UsuariosRepository.buscarPorId(claims.tenantId, userId)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("erro" to "usuário não encontrado"))
            val tenant = wms.backend.tenancy.TenantRepository.buscarPorId(claims.tenantId)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            try {
                val r = SankhyaUsuarioService.criar(tenant.slug, usuario.nome)
                UsuariosRepository.atualizar(
                    claims.tenantId, userId, AtualizarUsuarioRequest(alterarCodusuSankhya = true, codusuSankhya = r.codusu),
                )
                call.respond(
                    mapOf(
                        "codusu" to r.codusu.toString(),
                        "nomeUsu" to r.nomeUsu,
                        "mensagem" to if (r.jaExistia) "Já existia no Sankhya (${r.codusu} ${r.nomeUsu}) — vinculado." else "Criado no Sankhya: ${r.codusu} ${r.nomeUsu} — vinculado.",
                    ),
                )
            } catch (e: SankhyaUsuarioService.CriacaoIndisponivelException) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to "${e.message}"))
            } catch (e: SankhyaUsuarioService.SankhyaUsuarioException) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to "Sankhya: ${e.message}"))
            }
        }

        post("/{id}/crachao") {
            val claims = call.exigirAdmin() ?: return@post
            val userId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "id inválido"))

            val req = call.receive<DefinirCrachaRequest>()
            try {
                val definido = UsuariosRepository.definirCracha(claims.tenantId, userId, req.crachaoCodigo)
                if (!definido) {
                    call.respond(HttpStatusCode.NotFound, mapOf("erro" to "usuário não encontrado"))
                    return@post
                }
                call.respond(mapOf("ok" to true))
            } catch (e: CrachaoJaExisteException) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to e.message))
            }
        }

        post("/{id}/alterar-senha") {
            val claims = call.exigirAuth() ?: return@post
            val userId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "id inválido"))

            val ehAdmin = claims.perfil == "ADMINISTRADOR"
            val ehProprioUsuario = claims.userId == userId
            if (!ehAdmin && !ehProprioUsuario) {
                call.respond(HttpStatusCode.Forbidden, mapOf("erro" to "só é possível alterar a própria senha"))
                return@post
            }

            val req = call.receive<AlterarSenhaRequest>()
            // Admin alterando OUTRO usuário não precisa da senha atual; o
            // próprio usuário alterando a própria senha precisa informá-la.
            val senhaAtual = if (ehProprioUsuario) req.senhaAtual else null
            val sucesso = UsuariosRepository.alterarSenha(claims.tenantId, userId, senhaAtual, req.senhaNova)
            if (!sucesso) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "senha atual incorreta ou usuário não encontrado"))
                return@post
            }
            call.respond(mapOf("ok" to true))
        }
    }
}
