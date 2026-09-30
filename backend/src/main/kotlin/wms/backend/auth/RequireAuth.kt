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
        return null
    }
    // Conta de TV (perfil TV, ex.: "TV Expedição") só enxerga a TV — qualquer outra rota é barrada aqui,
    // num ponto só (toda rota protegida passa por exigirAuth/exigirAdmin).
    if (claims.perfil == PERFIL_TV && !request.local.uri.startsWith("/api/tv") && !request.local.uri.startsWith("/api/auth")) {
        respond(HttpStatusCode.Forbidden, mapOf("erro" to "conta de TV só acessa a TV de conferência"))
        return null
    }
    return claims
}

/** Perfil de conta de dispositivo da TV de expedição (tratada como estação: não é pessoa). */
const val PERFIL_TV = "TV"

/** Permissão TV_CONFERENCIA: perfil TV ou ADMINISTRADOR (sistema de permissão atual é por perfil). */
fun podeVerTv(claims: ClaimsToken): Boolean = claims.perfil == PERFIL_TV || claims.perfil == "ADMINISTRADOR"

/** Mesma coisa, mas também exige perfil ADMINISTRADOR. */
suspend fun ApplicationCall.exigirAdmin(): ClaimsToken? {
    val claims = exigirAuth() ?: return null
    if (claims.perfil != "ADMINISTRADOR") {
        respond(HttpStatusCode.Forbidden, mapOf("erro" to "requer perfil ADMINISTRADOR"))
        return null
    }
    return claims
}
