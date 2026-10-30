package wms.backend.master

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wms.backend.auth.ClaimsMaster
import wms.backend.auth.ClienteIp
import wms.backend.auth.FaixaIp
import wms.backend.auth.JwtService

/**
 * Restrição de rede do Painel Master (WMS_MASTER_REDES_PERMITIDAS, CIDR separado por vírgula — ex.: a faixa da VPN).
 * Vazio = sem restrição de rede. Valor inválido = ninguém entra (configuração errada não vira "libera tudo").
 * Fora da faixa a API da plataforma responde 404, como se não existisse.
 */
object RedeMaster {
    private val faixas: List<FaixaIp>? by lazy { FaixaIp.lerLista(System.getenv("WMS_MASTER_REDES_PERMITIDAS")) }

    fun permitido(ip: String, faixasConfiguradas: List<FaixaIp>? = faixas): Boolean = when {
        faixasConfiguradas == null -> false
        faixasConfiguradas.isEmpty() -> true
        else -> faixasConfiguradas.any { it.contem(ip) }
    }
}

/**
 * Porta comum de /api/master/... (inclusive o login): chave Master configurada e rede permitida. Responde e
 * devolve false quando não passa.
 */
suspend fun ApplicationCall.plataformaDisponivel(): Boolean {
    if (JwtService.motivoMasterIndisponivel() != null) {
        respond(HttpStatusCode.ServiceUnavailable, mapOf("erro" to "administração da plataforma não configurada nesta instalação"))
        return false
    }
    if (!RedeMaster.permitido(ClienteIp.de(this))) {
        respond(HttpStatusCode.NotFound, mapOf("erro" to "não encontrado"))
        return false
    }
    return true
}

/**
 * Guarda de TODA rota da plataforma (/api/master/...). Aceita só token Master (chave própria, emissor/audiência
 * "wms-plataforma"/"wms-master", escopo "plataforma", emitido pelo login Master) de um Master que continua ativo em
 * tenancy.master_users. Token de usuário de tenant — inclusive ADMINISTRADOR — é recusado aqui sem consultar nada.
 */
suspend fun ApplicationCall.exigirMaster(): ClaimsMaster? {
    if (!plataformaDisponivel()) return null
    val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim().orEmpty()
    val claims = token.takeIf { it.isNotBlank() }?.let(JwtService::validarMaster)
    if (claims == null) {
        if (token.isNotBlank()) application.environment.log.warn("Painel Master: token recusado em ${request.local.uri} (ip ${ClienteIp.de(this)})")
        respond(HttpStatusCode.Unauthorized, mapOf("erro" to "acesso restrito à administração da plataforma"))
        return null
    }
    if (withContext(Dispatchers.IO) { MasterRepository.ativo(claims.masterId) } == null) {
        respond(HttpStatusCode.Unauthorized, mapOf("erro" to "usuário da plataforma desativado"))
        return null
    }
    return claims
}

fun ApplicationCall.atorMaster(claims: ClaimsMaster) =
    AtorAdmin(tipo = "master", id = claims.masterId, email = claims.email, ip = ClienteIp.de(this))

/**
 * Confirmação de alteração crítica: o Master digita o SLUG do tenant que está alterando. Evita mexer no cliente
 * errado (aba trocada, id colado de outro) — o backend confere, não só a tela.
 */
object ConfirmacaoCritica {
    fun valida(slugDoTenant: String, informado: String?): Boolean = informado?.trim() == slugDoTenant
}
