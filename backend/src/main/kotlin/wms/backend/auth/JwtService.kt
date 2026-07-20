package wms.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.time.Instant
import java.util.Date
import java.util.UUID

data class ClaimsToken(val userId: UUID, val tenantId: UUID, val perfil: String)

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

    fun gerar(userId: UUID, tenantId: UUID, perfil: String): String =
        JWT.create()
            .withSubject(userId.toString())
            .withClaim("tenant_id", tenantId.toString())
            .withClaim("perfil", perfil)
            .withExpiresAt(Date.from(Instant.now().plusSeconds(EXPIRACAO_SEGUNDOS)))
            .sign(algorithm)

    fun validar(token: String): ClaimsToken? = try {
        val verificado = JWT.require(algorithm).build().verify(token)
        ClaimsToken(
            userId = UUID.fromString(verificado.subject),
            tenantId = UUID.fromString(verificado.getClaim("tenant_id").asString()),
            perfil = verificado.getClaim("perfil").asString(),
        )
    } catch (e: JWTVerificationException) {
        null
    } catch (e: IllegalArgumentException) {
        null // UUID.fromString falhou — token malformado
    }
}
