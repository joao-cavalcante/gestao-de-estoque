package wms.backend.master

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.update
import wms.backend.tenancy.CredentialsCipher
import java.time.Duration
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
    /** V68: segredo TOTP cifrado (CredentialsCipher) e último passo aceito. */
    val mfaSegredo = text("mfa_segredo").nullable()
    val mfaUltimoPasso = long("mfa_ultimo_passo").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** tenancy.master_login_tentativas (V68) — base do limite de tentativas do login Master. */
object MasterLoginTentativasTable : Table("tenancy.master_login_tentativas") {
    val id = long("id")
    val ocorridoEm = timestamp("ocorrido_em")
    val email = text("email")
    val ip = text("ip")
    val sucesso = bool("sucesso")
    val bloqueada = bool("bloqueada")

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

/** Visão da ferramenta CriarMasterKt — sem hash e sem segredo. */
data class MasterResumo(val id: UUID, val email: String, val nome: String, val ativo: Boolean, val mfaCadastrado: Boolean)

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

    /** `mfaSegredo` já decifrado (só existe em memória, durante o login). */
    data class MasterLogin(val id: UUID, val nome: String, val email: String, val senhaHash: String, val ativo: Boolean, val mfaSegredo: String? = null)

    fun normalizarEmail(email: String) = email.trim().lowercase().take(254)

    fun buscarParaLogin(email: String): MasterLogin? = transaction {
        MasterUsersTable.selectAll().where { MasterUsersTable.email eq normalizarEmail(email) }.singleOrNull()?.let {
            MasterLogin(
                it[MasterUsersTable.id], it[MasterUsersTable.nome], it[MasterUsersTable.email], it[MasterUsersTable.senhaHash], it[MasterUsersTable.ativo],
                it[MasterUsersTable.mfaSegredo]?.let(CredentialsCipher::decrypt),
            )
        }
    }

    /** Master continua existindo e ativo? Conferido a cada chamada: desativar um Master corta o acesso na hora. */
    fun ativo(id: UUID): MasterDto? = transaction {
        MasterUsersTable.selectAll().where { MasterUsersTable.id eq id }.singleOrNull()
            ?.takeIf { it[MasterUsersTable.ativo] }
            ?.let { MasterDto(it[MasterUsersTable.id].toString(), it[MasterUsersTable.nome], it[MasterUsersTable.email]) }
    }

    fun validarSenha(senha: String, hash: String): Boolean = runCatching { BCrypt.checkpw(senha, hash) }.getOrDefault(false)

    fun listar(): List<MasterResumo> = transaction {
        MasterUsersTable.selectAll().orderBy(MasterUsersTable.criadoEm).map {
            MasterResumo(it[MasterUsersTable.id], it[MasterUsersTable.email], it[MasterUsersTable.nome], it[MasterUsersTable.ativo], it[MasterUsersTable.mfaSegredo] != null)
        }
    }

    /** Só pela ferramenta CriarMasterKt (dentro do container) — não existe rota pra isto. */
    fun criar(email: String, nome: String, senha: String, mfaSegredo: String?): UUID = transaction {
        val id = UUID.randomUUID()
        MasterUsersTable.insert {
            it[MasterUsersTable.id] = id
            it[MasterUsersTable.email] = normalizarEmail(email)
            it[MasterUsersTable.nome] = nome.trim()
            it[senhaHash] = BCrypt.hashpw(senha, BCrypt.gensalt())
            it[ativo] = true
            it[criadoEm] = Instant.now()
            it[MasterUsersTable.mfaSegredo] = mfaSegredo?.let(CredentialsCipher::encrypt)
        }
        id
    }

    fun redefinirSenha(email: String, senha: String): Boolean = transaction {
        MasterUsersTable.update({ MasterUsersTable.email eq normalizarEmail(email) }) { it[senhaHash] = BCrypt.hashpw(senha, BCrypt.gensalt()) } == 1
    }

    /** Segredo novo (o anterior para de valer na hora). null = remove o MFA da conta. */
    fun redefinirMfa(email: String, mfaSegredo: String?): Boolean = transaction {
        MasterUsersTable.update({ MasterUsersTable.email eq normalizarEmail(email) }) {
            it[MasterUsersTable.mfaSegredo] = mfaSegredo?.let(CredentialsCipher::encrypt)
            it[mfaUltimoPasso] = null
        } == 1
    }

    fun definirAtivo(email: String, ativo: Boolean): Boolean = transaction {
        MasterUsersTable.update({ MasterUsersTable.email eq normalizarEmail(email) }) { it[MasterUsersTable.ativo] = ativo } == 1
    }

    /**
     * Grava o passo TOTP usado, só se for posterior ao último aceito — atômico no banco (vale com duas réplicas).
     * false = código já usado (ou de um passo anterior ao último login).
     */
    fun consumirPassoMfa(id: UUID, passo: Long): Boolean = transaction {
        MasterUsersTable.update({
            (MasterUsersTable.id eq id) and (MasterUsersTable.mfaUltimoPasso.isNull() or (MasterUsersTable.mfaUltimoPasso less passo))
        }) { it[mfaUltimoPasso] = passo } == 1
    }

    // ─── Tentativas de login (V68) ────────────────────────────────────────

    fun registrarTentativa(email: String, ip: String, sucesso: Boolean, bloqueada: Boolean) {
        transaction {
            MasterLoginTentativasTable.insert {
                it[MasterLoginTentativasTable.email] = normalizarEmail(email)
                it[MasterLoginTentativasTable.ip] = ip.take(64)
                it[MasterLoginTentativasTable.sucesso] = sucesso
                it[MasterLoginTentativasTable.bloqueada] = bloqueada
            }
        }
    }

    /**
     * Falhas que contam pro limite, desde `desde`: do par (e-mail, IP) e do e-mail só depois do último login com
     * sucesso daquela conta; do IP, todas as da janela. Tentativa recusada pelo próprio limite não conta.
     */
    fun contarFalhas(email: String, ip: String, desde: Instant): ContagemFalhas = transaction {
        val e = normalizarEmail(email)
        val t = MasterLoginTentativasTable
        // "Depois do último sucesso" pela ordem de gravação (id), não pelo horário: dois registros no mesmo instante não se confundem.
        val maximo = t.id.max()
        val ultimoSucesso = t.select(maximo).where { (t.email eq e) and (t.sucesso eq true) }.singleOrNull()?.get(maximo) ?: 0L
        val ipsDasFalhasDaConta = t.select(t.ip)
            .where { (t.email eq e) and (t.sucesso eq false) and (t.bloqueada eq false) and (t.ocorridoEm greater desde) and (t.id greater ultimoSucesso) }
            .map { it[t.ip] }
        val falhasDoIp = t.selectAll()
            .where { (t.ip eq ip) and (t.sucesso eq false) and (t.bloqueada eq false) and (t.ocorridoEm greater desde) }
            .count()
        ContagemFalhas(par = ipsDasFalhasDaConta.count { it == ip }, email = ipsDasFalhasDaConta.size, ip = falhasDoIp.toInt())
    }

    /** Recuperação administrativa (CriarMasterKt desbloquear): zera as falhas daquele e-mail. */
    fun limparFalhas(email: String): Int = transaction {
        MasterLoginTentativasTable.deleteWhere { (MasterLoginTentativasTable.email eq normalizarEmail(email)) and (MasterLoginTentativasTable.sucesso eq false) }
    }

    /** Tentativas com mais de 30 dias saem (a trilha permanente é a auditoria_admin). */
    fun limparTentativasAntigas(): Int = transaction {
        MasterLoginTentativasTable.deleteWhere { MasterLoginTentativasTable.ocorridoEm less Instant.now().minus(Duration.ofDays(30)) }
    }
}

/** Ator de uma ação administrativa. */
data class AtorAdmin(val tipo: String, val id: UUID?, val email: String?, val ip: String?)

object AuditoriaAdmin {
    private val json = Json { encodeDefaults = true }

    /** Chaves que nunca vão pra trilha, em qualquer nível do detalhe. */
    private val CHAVES_SECRETAS = setOf(
        "senha", "credenciais", "clientsecret", "client_secret", "xtoken", "x-token", "token", "password",
        "codigo", "mfa", "mfasegredo", "mfa_segredo", "segredo", "secret", "otpauth",
    )

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
