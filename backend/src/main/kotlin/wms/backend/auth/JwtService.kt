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
 * Chave do token Master (WMS_MASTER_JWT_SECRET). Própria, obrigatória e SEM reserva: ausente, curta ou igual ao
 * segredo dos clientes = administração da plataforma indisponível (rotas /api/master respondem 503), nunca uma
 * assinatura com o segredo dos tenants. O resto do WMS segue funcionando sem ela.
 */
sealed interface ConfigMasterJwt {
    class Pronta(val algoritmo: Algorithm) : ConfigMasterJwt
    class Indisponivel(val motivo: String) : ConfigMasterJwt

    companion object {
        const val TAMANHO_MINIMO = 32

        fun carregar(segredoMaster: String?, segredoTenants: String): ConfigMasterJwt = when {
            segredoMaster.isNullOrBlank() -> Indisponivel("WMS_MASTER_JWT_SECRET não configurado")
            segredoMaster.length < TAMANHO_MINIMO -> Indisponivel("WMS_MASTER_JWT_SECRET com menos de $TAMANHO_MINIMO caracteres")
            segredoMaster == segredoTenants -> Indisponivel("WMS_MASTER_JWT_SECRET igual ao WMS_JWT_SECRET dos clientes")
            else -> Pronta(Algorithm.HMAC256(segredoMaster))
        }
    }
}

/**
 * JWT simples (com.auth0:java-jwt) — sem plugin de auth do Ktor, no mesmo
 * estilo manual já usado no resto do backend (TenantRoutes etc). Trade-off
 * assumido conscientemente: sem revogação instantânea (o sistema antigo,
 * com sessão em Redis, conseguia "matar sessão" na hora; aqui expira só
 * no `exp`, 8h) — aceitável pro escopo básico atual. Tenant suspenso perde o
 * acesso antes disso: exigirAuth confere a situação do tenant (EstadoTenant).
 */
object JwtService {
    private val secret = System.getenv("WMS_JWT_SECRET") ?: "CHANGE_ME_EM_PRODUCAO_JWT_SECRET"
    private val algorithm = Algorithm.HMAC256(secret)
    private const val EXPIRACAO_SEGUNDOS = 8L * 3600

    /** Token Master: chave, emissor e audiência próprios, escopo plataforma, sem tenant_id e sem perfil, validade menor. */
    private const val ESCOPO_PLATAFORMA = "plataforma"
    const val EMISSOR_MASTER = "wms-plataforma"
    const val AUDIENCIA_MASTER = "wms-master"
    private const val EXPIRACAO_MASTER_SEGUNDOS = 4L * 3600

    private val configMaster: ConfigMasterJwt by lazy { ConfigMasterJwt.carregar(System.getenv("WMS_MASTER_JWT_SECRET"), secret) }

    /** null = pronta; senão o motivo (sem o valor do segredo) de a administração da plataforma estar indisponível. */
    fun motivoMasterIndisponivel(): String? = (configMaster as? ConfigMasterJwt.Indisponivel)?.motivo

    fun gerar(userId: UUID, tenantId: UUID, perfil: String): String =
        JWT.create()
            .withSubject(userId.toString())
            .withClaim("tenant_id", tenantId.toString())
            .withClaim("perfil", perfil)
            .withExpiresAt(Date.from(Instant.now().plusSeconds(EXPIRACAO_SEGUNDOS)))
            .sign(algorithm)

    fun gerarMaster(masterId: UUID, email: String): String {
        val config = configMaster as? ConfigMasterJwt.Pronta
            ?: error("token Master indisponível: ${motivoMasterIndisponivel()}")
        val agora = Instant.now()
        return JWT.create()
            .withIssuer(EMISSOR_MASTER)
            .withAudience(AUDIENCIA_MASTER)
            .withSubject(masterId.toString())
            .withClaim("escopo", ESCOPO_PLATAFORMA)
            .withClaim("email", email)
            .withIssuedAt(Date.from(agora))
            .withExpiresAt(Date.from(agora.plusSeconds(EXPIRACAO_MASTER_SEGUNDOS)))
            .sign(config.algoritmo)
    }

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

    /** Token Master. Token de tenant (mesmo de ADMINISTRADOR) NUNCA passa aqui — nem a chave é a mesma. */
    fun validarMaster(token: String): ClaimsMaster? = try {
        val config = configMaster as? ConfigMasterJwt.Pronta ?: return null
        val verificado = JWT.require(config.algoritmo)
            .withIssuer(EMISSOR_MASTER)
            .withAudience(AUDIENCIA_MASTER)
            .withClaim("escopo", ESCOPO_PLATAFORMA)
            .build()
            .verify(token)
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
