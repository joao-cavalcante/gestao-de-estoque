package wms.backend.usuarios

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mindrot.jbcrypt.BCrypt
import wms.backend.Database as SharedDatabase
import wms.backend.tenancy.TenantTx
import wms.backend.tenancy.UserLoginLookupTable
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

class EmailJaExisteException(email: String) : Exception("e-mail '$email' já está em uso")

data class UsuarioParaLogin(val tenantId: UUID, val userId: UUID, val senhaHash: String?, val perfil: String, val ativo: Boolean, val nome: String, val email: String)

object UsuariosRepository {

    private fun normalizar(email: String) = email.trim().lowercase()

    /**
     * Cria em duas etapas: reserva o e-mail em `tenancy.user_login_lookup`
     * (sempre no banco central) primeiro — é o que garante unicidade
     * global — depois grava em `app.users` (compartilhado OU dedicado,
     * conforme o tenant). Se a segunda etapa falhar, desfaz a reserva
     * (compensação manual — não é uma transação distribuída de verdade,
     * aceitável porque hoje nenhum tenant é dedicated na prática).
     */
    fun criar(tenantId: UUID, req: CriarUsuarioRequest): UsuarioDto {
        val email = normalizar(req.email)
        val userId = UUID.randomUUID()
        val agora = Instant.now()
        val hash = BCrypt.hashpw(req.senha, BCrypt.gensalt())

        try {
            transaction(SharedDatabase.shared) {
                UserLoginLookupTable.insert {
                    it[UserLoginLookupTable.email] = email
                    it[UserLoginLookupTable.tenantId] = tenantId
                    it[UserLoginLookupTable.userId] = userId
                }
            }
        } catch (e: Exception) {
            throw EmailJaExisteException(email)
        }

        try {
            TenantTx.run(tenantId) {
                UsersTable.insert {
                    it[id] = userId
                    it[UsersTable.tenantId] = tenantId
                    it[nome] = req.nome
                    it[UsersTable.email] = email
                    it[senhaHash] = hash
                    it[perfil] = req.perfil
                    it[ativo] = true
                    it[criadoEm] = agora
                    it[atualizadoEm] = agora
                }
            }
        } catch (e: Exception) {
            transaction(SharedDatabase.shared) {
                UserLoginLookupTable.deleteWhere { UserLoginLookupTable.email eq email }
            }
            throw e
        }

        return UsuarioDto(userId.toString(), req.nome, email, req.perfil, true)
    }

    fun listar(tenantId: UUID): List<UsuarioDto> = TenantTx.run(tenantId) {
        UsersTable.selectAll()
            .where { UsersTable.tenantId eq tenantId }
            .orderBy(UsersTable.nome)
            .map { it.toDto() }
    }

    fun atualizar(tenantId: UUID, userId: UUID, req: AtualizarUsuarioRequest): Boolean = TenantTx.run(tenantId) {
        val linhas = UsersTable.update({ (UsersTable.tenantId eq tenantId) and (UsersTable.id eq userId) }) {
            req.nome?.let { v -> it[nome] = v }
            req.perfil?.let { v -> it[perfil] = v }
            req.ativo?.let { v -> it[ativo] = v }
            it[atualizadoEm] = Instant.now()
        }
        linhas > 0
    }

    /**
     * `senhaAtual` != null: o próprio usuário troca a senha (valida a
     * atual). `senhaAtual` == null: um ADMINISTRADOR troca a senha de
     * outro usuário (sem precisar saber a atual) — checagem de perfil já
     * feita na rota antes de chamar isto.
     */
    fun alterarSenha(tenantId: UUID, userId: UUID, senhaAtual: String?, senhaNova: String): Boolean = TenantTx.run(tenantId) {
        val row = UsersTable.selectAll().where { (UsersTable.tenantId eq tenantId) and (UsersTable.id eq userId) }.singleOrNull()
            ?: return@run false

        if (senhaAtual != null) {
            val hashAtual = row[UsersTable.senhaHash] ?: return@run false
            if (!BCrypt.checkpw(senhaAtual, hashAtual)) return@run false
        }

        UsersTable.update({ (UsersTable.tenantId eq tenantId) and (UsersTable.id eq userId) }) {
            it[senhaHash] = BCrypt.hashpw(senhaNova, BCrypt.gensalt())
            it[atualizadoEm] = Instant.now()
        }
        true
    }

    /** Remove o usuário e libera o e-mail no lookup global (outro tenant pode usá-lo depois). */
    fun remover(tenantId: UUID, userId: UUID): Boolean {
        val row = TenantTx.run(tenantId) {
            UsersTable.selectAll().where { (UsersTable.tenantId eq tenantId) and (UsersTable.id eq userId) }.singleOrNull()
        } ?: return false

        TenantTx.run(tenantId) {
            UsersTable.deleteWhere { (UsersTable.tenantId eq tenantId) and (UsersTable.id eq userId) }
        }
        transaction(SharedDatabase.shared) {
            UserLoginLookupTable.deleteWhere { UserLoginLookupTable.email eq row[UsersTable.email] }
        }
        return true
    }

    /** Resolve tenant a partir do e-mail (banco central), depois busca o usuário no banco certo. */
    fun buscarParaLogin(email: String): UsuarioParaLogin? {
        val emailNormalizado = normalizar(email)
        val lookup = transaction(SharedDatabase.shared) {
            UserLoginLookupTable.selectAll().where { UserLoginLookupTable.email eq emailNormalizado }.singleOrNull()
        } ?: return null

        val tenantId = lookup[UserLoginLookupTable.tenantId]
        val userId = lookup[UserLoginLookupTable.userId]

        val row = TenantTx.run(tenantId) {
            UsersTable.selectAll().where { UsersTable.id eq userId }.singleOrNull()
        } ?: return null

        return UsuarioParaLogin(
            tenantId = tenantId,
            userId = userId,
            senhaHash = row[UsersTable.senhaHash],
            perfil = row[UsersTable.perfil],
            ativo = row[UsersTable.ativo],
            nome = row[UsersTable.nome],
            email = row[UsersTable.email],
        )
    }

    fun validarSenha(senhaPlana: String, hash: String?): Boolean =
        hash != null && BCrypt.checkpw(senhaPlana, hash)

    /** Gera token de "esqueci senha" (30min) — envio de e-mail é TODO (sem SMTP configurado ainda). */
    fun gerarTokenReset(email: String): String? {
        val emailNormalizado = normalizar(email)
        val lookup = transaction(SharedDatabase.shared) {
            UserLoginLookupTable.selectAll().where { UserLoginLookupTable.email eq emailNormalizado }.singleOrNull()
        } ?: return null

        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(SecureRandom().generateSeed(24))
        val tenantId = lookup[UserLoginLookupTable.tenantId]
        val userId = lookup[UserLoginLookupTable.userId]

        TenantTx.run(tenantId) {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[resetToken] = token
                it[resetTokenExpira] = Instant.now().plusSeconds(30 * 60)
            }
        }
        return token
    }

    /** Token de reset não carrega o tenant — a rota pública exige e-mail + token juntos. */
    fun redefinirComTokenEEmail(email: String, token: String, senhaNova: String): Boolean {
        val emailNormalizado = normalizar(email)
        val lookup = transaction(SharedDatabase.shared) {
            UserLoginLookupTable.selectAll().where { UserLoginLookupTable.email eq emailNormalizado }.singleOrNull()
        } ?: return false

        val tenantId = lookup[UserLoginLookupTable.tenantId]
        val userId = lookup[UserLoginLookupTable.userId]

        return TenantTx.run(tenantId) {
            val row = UsersTable.selectAll().where { UsersTable.id eq userId }.singleOrNull() ?: return@run false
            val tokenValido = row[UsersTable.resetToken] == token
            val expira = row[UsersTable.resetTokenExpira]
            val naoExpirou = expira != null && expira.isAfter(Instant.now())
            if (!tokenValido || !naoExpirou) return@run false

            UsersTable.update({ UsersTable.id eq userId }) {
                it[senhaHash] = BCrypt.hashpw(senhaNova, BCrypt.gensalt())
                it[resetToken] = null
                it[resetTokenExpira] = null
                it[atualizadoEm] = Instant.now()
            }
            true
        }
    }

    private fun ResultRow.toDto() = UsuarioDto(
        id = this[UsersTable.id].toString(),
        nome = this[UsersTable.nome],
        email = this[UsersTable.email],
        perfil = this[UsersTable.perfil],
        ativo = this[UsersTable.ativo],
    )
}
