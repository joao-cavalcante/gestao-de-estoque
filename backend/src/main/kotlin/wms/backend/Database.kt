package wms.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database as ExposedDatabase

/**
 * Conexão única, compartilhada entre todos os tenants (modelo shared-schema
 * + RLS — ver db/README.md na raiz do repo). NÃO existe pool por tenant
 * aqui: é exatamente o que resolve o problema de explosão de conexões do
 * sistema anterior (1 PrismaClient por tenant, sem eviction).
 *
 * Tenants "dedicated" (tier enterprise) terão uma DataSource própria
 * resolvida em runtime a partir de tenancy.tenants.dedicated_db_url — ainda
 * não implementado nesta fase (fundação cobre só o pool compartilhado).
 */
object Database {
    /**
     * Handle nomeado do banco compartilhado — usado explicitamente por
     * TenantDatabaseRouter (que decide, por tenant, se usa este ou um
     * Database dedicado). `Database.connect` também registra isto como
     * default global do Exposed, mas contar só com o default implícito
     * quebraria no dia em que um tenant dedicated precisar de outra conexão
     * na mesma call stack.
     */
    lateinit var shared: ExposedDatabase
        private set

    fun init() {
        val url = System.getenv("WMS_DB_URL") ?: "jdbc:postgresql://localhost:5432/wms"
        val user = System.getenv("WMS_DB_USER") ?: "wms_app"
        val password = System.getenv("WMS_DB_PASSWORD") ?: "CHANGE_ME_EM_PRODUCAO"

        val poolSize = System.getenv("WMS_DB_POOL_SIZE")?.toIntOrNull() ?: 20
        shared = conectar(url, user, password, poolSize)
    }

    /**
     * Usado pelo TenantDatabaseRouter pra criar a conexão de um tenant
     * dedicated — pool pequeno por padrão (só aquele tenant usa), bem
     * diferente do pool do banco compartilhado (usado por todo mundo).
     */
    fun conectar(url: String, user: String, password: String, poolSize: Int = 20): ExposedDatabase {
        val config = HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = poolSize
            isAutoCommit = false
            // RLS depende de SET LOCAL por transação (compatível com PgBouncer
            // em modo transaction pooling) — autoCommit=false aqui garante que
            // toda operação passe por uma transação explícita do Exposed.
        }
        return ExposedDatabase.connect(HikariDataSource(config))
    }
}
