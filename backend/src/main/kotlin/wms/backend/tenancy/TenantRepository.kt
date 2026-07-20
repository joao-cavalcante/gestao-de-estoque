package wms.backend.tenancy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.time.Instant
import java.util.UUID

/**
 * Acesso a tenancy.tenants / tenancy.erp_connections. Estas tabelas são o
 * PLANO DE CONTROLE — não são por-tenant e não passam por RLS (são elas que
 * definem quem são os tenants). Diferente de qualquer tabela futura em
 * `app.*`, aqui não existe SET LOCAL app.tenant_id nem contexto de tenant.
 *
 * `credenciais` (client_id/client_secret/x-token) é cifrada em repouso
 * (CredentialsCipher, AES-256-GCM) e NUNCA decifrada em direção a uma
 * resposta de API — só internamente, quando o backend for de fato chamar
 * o ERP do tenant (ainda não implementado nesta fase).
 */
object TenantRepository {

    fun listar(): List<TenantDto> = transaction {
        val tenants = TenantsTable.selectAll().map { it.toDto() }
        if (tenants.isEmpty()) return@transaction tenants

        // Uma query só pra todas as conexões, agrupada em memória por
        // tenant_id — evita N+1 (uma query de erp_connections por tenant)
        // conforme a lista cresce.
        val conexoesPorTenant = ErpConnectionsTable.selectAll()
            .groupBy({ it[ErpConnectionsTable.tenantId].toString() }, { it.toErpDto() })

        tenants.map { tenant -> tenant.copy(erpConnections = conexoesPorTenant[tenant.id] ?: emptyList()) }
    }

    fun buscarPorSlug(slug: String): TenantDto? = transaction {
        buscarPorSlugInterno(slug)
    }

    /** Usado pelo TenantDatabaseRouter — resolver tier/dedicated_db_url a partir só do UUID. */
    fun buscarPorId(id: UUID): TenantDto? = transaction {
        val tenantRow = TenantsTable.selectAll().where { TenantsTable.id eq id }.singleOrNull()
            ?: return@transaction null
        val conexoes = ErpConnectionsTable.selectAll()
            .where { ErpConnectionsTable.tenantId eq id }
            .map { it.toErpDto() }
        tenantRow.toDto().copy(erpConnections = conexoes)
    }

    fun criar(req: CriarTenantRequest): TenantDto = transaction {
        val id = UUID.randomUUID()
        val agora = Instant.now()

        TenantsTable.insert {
            it[TenantsTable.id] = id
            it[slug] = req.slug
            it[nome] = req.nome
            it[tier] = req.tier
            it[status] = "trial"
            it[dedicatedDbUrl] = req.dedicatedDbUrl
            it[criadoEm] = agora
            it[atualizadoEm] = agora
        }

        req.erpConnections.forEach { conn -> upsertErpConnection(id, conn) }

        buscarPorSlugInterno(req.slug)!!
    }

    fun atualizar(slug: String, req: AtualizarTenantRequest): TenantDto? = transaction {
        val tenantRow = TenantsTable.selectAll().where { TenantsTable.slug eq slug }.singleOrNull()
            ?: return@transaction null
        val id = tenantRow[TenantsTable.id]

        TenantsTable.update({ TenantsTable.id eq id }) {
            req.nome?.let { v -> it[nome] = v }
            req.status?.let { v -> it[status] = v }
            req.tier?.let { v -> it[tier] = v }
            req.dedicatedDbUrl?.let { v -> it[dedicatedDbUrl] = v }
            it[atualizadoEm] = Instant.now()
        }

        buscarPorSlugInterno(slug)
    }

    /**
     * Cria OU atualiza a conexão daquele erpType pro tenant (upsert por
     * (tenant_id, erp_type), que é a unique constraint da tabela). Isto
     * importa especialmente pra credenciais: reenviar client_secret/x-token
     * pra "trocar a senha" precisa substituir a linha existente, não violar
     * a constraint tentando inserir de novo.
     */
    fun adicionarErpConnection(slug: String, conn: ErpConnectionInput): TenantDto? = transaction {
        val tenantRow = TenantsTable.selectAll().where { TenantsTable.slug eq slug }.singleOrNull()
            ?: return@transaction null
        upsertErpConnection(tenantRow[TenantsTable.id], conn)
        buscarPorSlugInterno(slug)
    }

    fun remover(slug: String): Boolean = transaction {
        val linhas = TenantsTable.deleteWhere { TenantsTable.slug eq slug }
        linhas > 0
    }

    // ─── internos ────────────────────────────────────────────────────────

    private fun buscarPorSlugInterno(slug: String): TenantDto? {
        val tenantRow = TenantsTable.selectAll().where { TenantsTable.slug eq slug }.singleOrNull()
            ?: return null
        val conexoes = ErpConnectionsTable.selectAll()
            .where { ErpConnectionsTable.tenantId eq tenantRow[TenantsTable.id] }
            .map { it.toErpDto() }
        return tenantRow.toDto().copy(erpConnections = conexoes)
    }

    /**
     * conn.credenciais == null significa "não mexe no segredo já salvo" —
     * é o caso da tela de edição, que nunca recebe de volta o segredo em
     * texto plano e por isso não tem como reenviá-lo. Só quando o admin
     * preenche os campos de credencial de novo (conn.credenciais != null)
     * é que o blob cifrado é substituído.
     */
    private fun upsertErpConnection(tenantId: UUID, conn: ErpConnectionInput) {
        val agora = Instant.now()
        val existente = ErpConnectionsTable.selectAll()
            .where { (ErpConnectionsTable.tenantId eq tenantId) and (ErpConnectionsTable.erpType eq conn.erpType) }
            .singleOrNull()

        if (existente != null && conn.credenciais == null) {
            ErpConnectionsTable.update({
                (ErpConnectionsTable.tenantId eq tenantId) and (ErpConnectionsTable.erpType eq conn.erpType)
            }) {
                it[baseUrl] = conn.baseUrl
                it[gatewayPath] = conn.gatewayPath
                it[dialect] = conn.dialect
                it[ativo] = conn.ativo
                it[atualizadoEm] = agora
            }
            return
        }

        val cifrado = CredentialsCipher.encrypt(conn.credenciais ?: "{}")
        ErpConnectionsTable.upsert(ErpConnectionsTable.tenantId, ErpConnectionsTable.erpType) {
            it[id] = UUID.randomUUID()
            it[ErpConnectionsTable.tenantId] = tenantId
            it[erpType] = conn.erpType
            it[baseUrl] = conn.baseUrl
            it[gatewayPath] = conn.gatewayPath
            it[dialect] = conn.dialect
            it[credenciais] = cifrado
            it[ativo] = conn.ativo
            it[criadoEm] = agora
            it[atualizadoEm] = agora
        }
    }

    /**
     * Credenciais DECIFRADAS de uma conexão de ERP — só pra uso interno do
     * backend (ex.: SankhyaAuthService indo autenticar de verdade no ERP
     * do tenant). NUNCA expor isto por uma rota; o resultado nem deveria
     * viver além do escopo da chamada que precisa dele.
     */
    fun obterCredenciaisErp(slug: String, erpType: String): ErpCredentials? = transaction {
        val tenantRow = TenantsTable.selectAll().where { TenantsTable.slug eq slug }.singleOrNull()
            ?: return@transaction null
        val row = ErpConnectionsTable.selectAll()
            .where { (ErpConnectionsTable.tenantId eq tenantRow[TenantsTable.id]) and (ErpConnectionsTable.erpType eq erpType) }
            .singleOrNull()
            ?: return@transaction null

        val decifrado = CredentialsCipher.decrypt(row[ErpConnectionsTable.credenciais])
        val obj = Json.parseToJsonElement(decifrado).jsonObject
        ErpCredentials(
            baseUrl = row[ErpConnectionsTable.baseUrl],
            gatewayPath = row[ErpConnectionsTable.gatewayPath],
            dialect = row[ErpConnectionsTable.dialect],
            clientId = (obj["clientId"] as? JsonPrimitive)?.contentOrNull,
            clientSecret = (obj["clientSecret"] as? JsonPrimitive)?.contentOrNull,
            xToken = (obj["xToken"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    private fun ResultRow.toDto() = TenantDto(
        id = this[TenantsTable.id].toString(),
        slug = this[TenantsTable.slug],
        nome = this[TenantsTable.nome],
        tier = this[TenantsTable.tier],
        status = this[TenantsTable.status],
        dedicatedDbUrl = this[TenantsTable.dedicatedDbUrl],
    )

    private fun ResultRow.toErpDto(): ErpConnectionDto {
        val configurada = try {
            val decifrado = CredentialsCipher.decrypt(this[ErpConnectionsTable.credenciais])
            Json.parseToJsonElement(decifrado).jsonObject.values
                .any { (it as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true }
        } catch (e: Exception) {
            false // blob vazio/corrompido -> trata como "não configurado", nunca propaga o erro pra fora
        }
        return ErpConnectionDto(
            erpType = this[ErpConnectionsTable.erpType],
            baseUrl = this[ErpConnectionsTable.baseUrl],
            gatewayPath = this[ErpConnectionsTable.gatewayPath],
            dialect = this[ErpConnectionsTable.dialect],
            ativo = this[ErpConnectionsTable.ativo],
            credenciaisConfiguradas = configurada,
        )
    }
}
