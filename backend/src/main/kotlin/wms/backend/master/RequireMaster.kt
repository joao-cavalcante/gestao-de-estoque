package wms.backend.master

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import wms.backend.auth.ClaimsMaster
import wms.backend.auth.JwtService

/**
 * Guarda de TODA rota da plataforma (/api/master/...). Aceita só token Master (escopo "plataforma", emitido pelo
 * login Master) de um Master que continua ativo em tenancy.master_users. Token de usuário de tenant — inclusive
 * ADMINISTRADOR — é recusado aqui sem consultar nada.
 */
suspend fun ApplicationCall.exigirMaster(): ClaimsMaster? {
    val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim().orEmpty()
    val claims = token.takeIf { it.isNotBlank() }?.let(JwtService::validarMaster)
    if (claims == null) {
        respond(HttpStatusCode.Unauthorized, mapOf("erro" to "acesso restrito à administração da plataforma"))
        return null
    }
    if (MasterRepository.ativo(claims.masterId) == null) {
        respond(HttpStatusCode.Unauthorized, mapOf("erro" to "usuário da plataforma desativado"))
        return null
    }
    return claims
}

fun ApplicationCall.atorMaster(claims: ClaimsMaster) =
    AtorAdmin(tipo = "master", id = claims.masterId, email = claims.email, ip = request.origin.remoteHost)

/**
 * Confirmação de alteração crítica: o Master digita o SLUG do tenant que está alterando. Evita mexer no cliente
 * errado (aba trocada, id colado de outro) — o backend confere, não só a tela.
 */
object ConfirmacaoCritica {
    fun valida(slugDoTenant: String, informado: String?): Boolean = informado?.trim() == slugDoTenant
}
