package wms.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.time.Instant
import java.util.Date
import java.util.UUID

data class ClaimsToken(val userId: UUID, val tenantId: UUID, val perfil: String)

/** Identidade da PLATAFORMA (tenancy.master_users) — não pertence a tenant nenhum e não tem perfil de tenant. */
data class ClaimsMaster(val masterId: UUID, val email: String)

/**
 * JWT simples (com.auth0:java-jwt) — sem plugin de auth do Ktor, no mesmo
 * estilo manual já usado no resto do backend (TenantRoutes etc). Trade-off
 * assumido conscientemente: sem revogação instantânea (o sistema antigo,
 * com sessão em Redis, conseguia "matar sessão" na hora; aqui expira só
 * no `exp`, 8h) — aceitável pro escopo básico atual.
 */
object JwtService {
    private val secret = System.getenv("WMS_JWT_SECRET") ?: "CHANGE_ME_EM_PRODUCAO_JWT_SECRET"
    private val algorithm = Algorithm.HMAC256(secret)
    private const val EXPIRACAO_SEGUNDOS = 8L * 3600

    /** Token Master: escopo próprio, sem tenant_id e sem perfil, validade menor. */
    private const val ESCOPO_PLATAFORMA = "plataforma"
    private const val EXPIRACAO_MASTER_SEGUNDOS = 4L * 3600

    fun gerar(userId: UUID, tenantId: UUID, perfil: String): String =
        JWT.create()
            .withSubject(userId.toString())
            .withClaim("tenant_id", tenantId.toString())
            .withClaim("perfil", perfil)
            .withExpiresAt(Date.from(Instant.now().plusSeconds(EXPIRACAO_SEGUNDOS)))
            .sign(algorithm)

    fun gerarMaster(masterId: UUID, email: String): String =
        JWT.create()
            .withSubject(masterId.toString())
            .withClaim("escopo", ESCOPO_PLATAFORMA)
            .withClaim("email", email)
            .withExpiresAt(Date.from(Instant.now().plusSeconds(EXPIRACAO_MASTER_SEGUNDOS)))
            .sign(algorithm)

    /** Token de usuário de tenant. Token Master (escopo plataforma) NUNCA passa aqui. */
    fun validar(token: String): ClaimsToken? = try {
        val verificado = JWT.require(algorithm).build().verify(token)
        val tenant = verificado.getClaim("tenant_id").asString()
        val perfil = verificado.getClaim("perfil").asString()
        if (!verificado.getClaim("escopo").isMissing || tenant == null || perfil == null) {
            null
        } else {
            ClaimsToken(userId = UUID.fromString(verificado.subject), tenantId = UUID.fromString(tenant), perfil = perfil)
        }
    } catch (e: JWTVerificationException) {
        null
    } catch (e: IllegalArgumentException) {
        null // UUID.fromString falhou — token malformado
    } catch (e: NullPointerException) {
        null // sem subject
    }

    /** Token Master. Token de tenant (mesmo de ADMINISTRADOR) NUNCA passa aqui. */
    fun validarMaster(token: String): ClaimsMaster? = try {
        val verificado = JWT.require(algorithm).withClaim("escopo", ESCOPO_PLATAFORMA).build().verify(token)
        val email = verificado.getClaim("email").asString()
        if (!verificado.getClaim("tenant_id").isMissing || !verificado.getClaim("perfil").isMissing || email == null) {
            null
        } else {
            ClaimsMaster(masterId = UUID.fromString(verificado.subject), email = email)
        }
    } catch (e: JWTVerificationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: NullPointerException) {
        null
    }
}
