package wms.backend.auth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

/** Extrai e valida o Bearer token, sem responder nada — só checagem. */
fun ApplicationCall.autenticado(): ClaimsToken? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    val token = header.removePrefix("Bearer ").trim()
    if (token.isBlank()) return null
    return JwtService.validar(token)
}

/**
 * Usar como `val claims = call.exigirAuth() ?: return@get` no início de
 * toda rota protegida — já responde 401 e a chamada `return@get` na rota
 * interrompe o handler.
 */
suspend fun ApplicationCall.exigirAuth(): ClaimsToken? {
    val claims = autenticado()
    if (claims == null) {
        respond(HttpStatusCode.Unauthorized, mapOf("erro" to "token ausente ou inválido"))
    }
    return claims
}

/** Mesma coisa, mas também exige perfil ADMINISTRADOR. */
suspend fun ApplicationCall.exigirAdmin(): ClaimsToken? {
    val claims = exigirAuth() ?: return null
    if (claims.perfil != "ADMINISTRADOR") {
        respond(HttpStatusCode.Forbidden, mapOf("erro" to "requer perfil ADMINISTRADOR"))
        return null
    }
    return claims
}
