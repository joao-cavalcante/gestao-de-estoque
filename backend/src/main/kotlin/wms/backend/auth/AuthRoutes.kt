package wms.backend.auth

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import wms.backend.tenancy.TenantRepository
import wms.backend.usuarios.LoginRequest
import wms.backend.usuarios.LoginResponse
import wms.backend.usuarios.UsuarioDto
import wms.backend.usuarios.UsuariosRepository
import java.util.UUID

@Serializable
data class EsqueciSenhaRequest(val email: String)

@Serializable
data class RedefinirSenhaRequest(val email: String, val token: String, val senhaNova: String)

/**
 * `tenant` = slug — diferente do e-mail, o crachá é único só POR tenant
 * (V32), não globalmente, então não dá pra resolver o tenant só pelo
 * código. O app (tablet/estação) já sabe o slug — salvo no dispositivo
 * desde o primeiro login normal feito ali (AuthService.aplicarSessao).
 */
@Serializable
data class LoginCrachaRequest(val tenant: String, val crachaoCodigo: String)

fun Route.authRoutes() {
    route("/api/auth") {

        post("/login") {
            val req = call.receive<LoginRequest>()
            val usuario = UsuariosRepository.buscarParaLogin(req.email)

            if (usuario == null || !usuario.ativo || !UsuariosRepository.validarSenha(req.senha, usuario.senhaHash)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("erro" to "e-mail ou senha inválidos"))
                return@post
            }

            val token = JwtService.gerar(usuario.userId, usuario.tenantId, usuario.perfil)
            val tenantSlug = TenantRepository.buscarPorId(usuario.tenantId)?.slug ?: ""
            call.respond(
                LoginResponse(
                    token = token,
                    usuario = UsuarioDto(usuario.userId.toString(), usuario.nome, usuario.email, usuario.perfil, usuario.ativo),
                    tenantSlug = tenantSlug,
                ),
            )
        }

        /**
         * Login por crachá — mesmas claims/token do login normal, só troca
         * o método de identificação (pensado pros tablets, ver
         * LoginCrachaRequest). 401 aqui é seguro: esta rota já está sob
         * /api/auth/, que o interceptor do front nunca trata como "sessão
         * morta" (diferente de identificar-operador em SeparacaoRoutes.kt).
         */
        post("/login-cracha") {
            val req = call.receive<LoginCrachaRequest>()

            val tenantId = TenantRepository.buscarPorSlug(req.tenant)?.id?.let { UUID.fromString(it) }
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '${req.tenant}' não encontrado"))
                return@post
            }

            val usuario = UsuariosRepository.buscarParaLoginCracha(tenantId, req.crachaoCodigo.trim())
            if (usuario == null || !usuario.ativo) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("erro" to "crachá não reconhecido"))
                return@post
            }

            val token = JwtService.gerar(usuario.userId, usuario.tenantId, usuario.perfil)
            call.respond(
                LoginResponse(
                    token = token,
                    usuario = UsuarioDto(usuario.userId.toString(), usuario.nome, usuario.email, usuario.perfil, usuario.ativo),
                    tenantSlug = req.tenant,
                ),
            )
        }

        // TODO: enviar e-mail de verdade — sem SMTP configurado ainda neste
        // backend. O mecanismo (token + expiração de 30min) já funciona;
        // só falta a chamada real de envio.
        post("/esqueci-senha") {
            val req = call.receive<EsqueciSenhaRequest>()
            val token = UsuariosRepository.gerarTokenReset(req.email)
            // Resposta genérica sempre (não confirma se o e-mail existe — evita enumeração de usuários).
            call.respond(mapOf("ok" to true))
            if (token != null) {
                call.application.environment.log.info("Token de reset gerado para ${req.email} (envio de e-mail pendente de implementar)")
            }
        }

        post("/redefinir-senha") {
            val req = call.receive<RedefinirSenhaRequest>()
            val sucesso = UsuariosRepository.redefinirComTokenEEmail(req.email, req.token, req.senhaNova)
            if (!sucesso) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "token inválido ou expirado"))
                return@post
            }
            call.respond(mapOf("ok" to true))
        }
    }
}
