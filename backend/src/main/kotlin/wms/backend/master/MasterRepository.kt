package wms.backend.master

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import wms.backend.tarefas.JsonbColumnType
import java.time.Instant
import java.util.UUID

/** tenancy.master_users (V1) — administradores da plataforma, sem tenant. */
object MasterUsersTable : Table("tenancy.master_users") {
    val id = uuid("id")
    val email = text("email")
    val nome = text("nome")
    val senhaHash = text("senha_hash")
    val ativo = bool("ativo")
    val criadoEm = timestamp("criado_em")

    override val primaryKey = PrimaryKey(id)
}

/** tenancy.auditoria_admin (V67) — só INSERT/SELECT. */
object AuditoriaAdminTable : Table("tenancy.auditoria_admin") {
    val id = long("id")
    val ocorridoEm = timestamp("ocorrido_em")
    val atorTipo = text("ator_tipo")
    val atorId = uuid("ator_id").nullable()
    val atorEmail = text("ator_email").nullable()
    val tenantId = uuid("tenant_id").nullable()
    val tenantSlug = text("tenant_slug").nullable()
    val acao = text("acao")
    val resultado = text("resultado")
    val detalhe = registerColumn("detalhe", JsonbColumnType())
    val ip = text("ip").nullable()

    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class MasterDto(val id: String, val nome: String, val email: String)

@Serializable
data class RegistroAuditoriaDto(
    val id: Long,
    val ocorridoEm: String,
    val atorEmail: String?,
    val tenantSlug: String?,
    val acao: String,
    val resultado: String,
    val detalhe: JsonObject,
    val ip: String?,
)

object MasterRepository {

    data class MasterLogin(val id: UUID, val nome: String, val email: String, val senhaHash: String, val ativo: Boolean)

    fun buscarParaLogin(email: String): MasterLogin? = transaction {
        MasterUsersTable.selectAll().where { MasterUsersTable.email eq email.trim().lowercase() }.singleOrNull()?.let {
            MasterLogin(it[MasterUsersTable.id], it[MasterUsersTable.nome], it[MasterUsersTable.email], it[MasterUsersTable.senhaHash], it[MasterUsersTable.ativo])
        }
    }

    /** Master continua existindo e ativo? Conferido a cada chamada: desativar um Master corta o acesso na hora. */
    fun ativo(id: UUID): MasterDto? = transaction {
        MasterUsersTable.selectAll().where { MasterUsersTable.id eq id }.singleOrNull()
            ?.takeIf { it[MasterUsersTable.ativo] }
            ?.let { MasterDto(it[MasterUsersTable.id].toString(), it[MasterUsersTable.nome], it[MasterUsersTable.email]) }
    }

    fun validarSenha(senha: String, hash: String): Boolean = runCatching { BCrypt.checkpw(senha, hash) }.getOrDefault(false)

    /** Só pela ferramenta CriarMasterKt (dentro do container) — não existe rota pra isto. */
    fun criar(email: String, nome: String, senha: String): UUID = transaction {
        val id = UUID.randomUUID()
        MasterUsersTable.insert {
            it[MasterUsersTable.id] = id
            it[MasterUsersTable.email] = email.trim().lowercase()
            it[MasterUsersTable.nome] = nome.trim()
            it[senhaHash] = BCrypt.hashpw(senha, BCrypt.gensalt())
            it[ativo] = true
            it[criadoEm] = Instant.now()
        }
        id
    }
}

/** Ator de uma ação administrativa. */
data class AtorAdmin(val tipo: String, val id: UUID?, val email: String?, val ip: String?)

object AuditoriaAdmin {
    private val json = Json { encodeDefaults = true }

    /** Chaves que nunca vão pra trilha, em qualquer nível do detalhe. */
    private val CHAVES_SECRETAS = setOf("senha", "credenciais", "clientsecret", "client_secret", "xtoken", "x-token", "token", "password")

    /** Remove recursivamente qualquer chave secreta — a trilha nunca guarda credencial. */
    fun sanitizar(detalhe: JsonObject): JsonObject = JsonObject(
        detalhe.filterKeys { it.lowercase() !in CHAVES_SECRETAS }
            .mapValues { (_, v) -> if (v is JsonObject) sanitizar(v) else v },
    )

    /** Grava a ação. Falha de gravação da trilha NÃO é silenciosa: propaga (a rota responde erro). */
    fun registrar(ator: AtorAdmin, tenantId: UUID?, tenantSlug: String?, acao: String, detalhe: JsonObject, resultado: String = "ok") {
        transaction {
            AuditoriaAdminTable.insert {
                it[atorTipo] = ator.tipo
                it[atorId] = ator.id
                it[atorEmail] = ator.email
                it[AuditoriaAdminTable.tenantId] = tenantId
                it[AuditoriaAdminTable.tenantSlug] = tenantSlug
                it[AuditoriaAdminTable.acao] = acao
                it[AuditoriaAdminTable.resultado] = resultado
                it[AuditoriaAdminTable.detalhe] = json.encodeToString(JsonObject.serializer(), sanitizar(detalhe))
                it[ip] = ator.ip
            }
        }
    }

    fun listar(tenantId: UUID?, limite: Int = 200): List<RegistroAuditoriaDto> = transaction {
        AuditoriaAdminTable.selectAll()
            .apply { if (tenantId != null) where { AuditoriaAdminTable.tenantId eq tenantId } }
            .orderBy(AuditoriaAdminTable.ocorridoEm, SortOrder.DESC)
            .limit(limite.coerceIn(1, 1000))
            .map {
                RegistroAuditoriaDto(
                    id = it[AuditoriaAdminTable.id],
                    ocorridoEm = it[AuditoriaAdminTable.ocorridoEm].toString(),
                    atorEmail = it[AuditoriaAdminTable.atorEmail],
                    tenantSlug = it[AuditoriaAdminTable.tenantSlug],
                    acao = it[AuditoriaAdminTable.acao],
                    resultado = it[AuditoriaAdminTable.resultado],
                    detalhe = runCatching { Json.parseToJsonElement(it[AuditoriaAdminTable.detalhe]).jsonObject }.getOrDefault(JsonObject(emptyMap())),
                    ip = it[AuditoriaAdminTable.ip],
                )
            }
    }
}
