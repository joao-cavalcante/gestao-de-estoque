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
            val claims = call.exigirAuth() ?: return@get
            call.respond(UsuariosRepository.listar(claims.tenantId))
        }

        post {
            val claims = call.exigirAdmin() ?: return@post
            val req = call.receive<CriarUsuarioRequest>()
            try {
                val criado = UsuariosRepository.criar(claims.tenantId, req)
                call.respond(HttpStatusCode.Created, criado)
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
            call.respond(mapOf("ok" to true))
        }

        delete("/{id}") {
            val claims = call.exigirAdmin() ?: return@delete
            val userId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "id inválido"))

            if (!UsuariosRepository.remover(claims.tenantId, userId)) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "usuário não encontrado"))
                return@delete
            }
            call.respond(HttpStatusCode.NoContent)
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
