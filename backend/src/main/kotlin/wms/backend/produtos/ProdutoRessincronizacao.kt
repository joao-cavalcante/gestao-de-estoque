package wms.backend.produtos

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import wms.backend.auth.exigirAdmin
import wms.backend.tenancy.TenantRepository
import wms.backend.tenancy.TenantTx
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * "Ressincronizar produtos" (tela de Configuração de Conferência, só admin): apaga o cache local de
 * produtos, códigos de barra e unidades alternativas do tenant e relê o catálogo do Sankhya em segundo
 * plano — o que antes era feito à mão no banco quando o cadastro do Sankhya era corrigido.
 * Unidades alternativas voltam sob demanda (primeira conferência/mapa de cada nota). Imagens ficam.
 * Conferência já aberta mantém os itens que carregou; vale pras próximas.
 */
object ProdutoRessincronizacao {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Serializable
    data class StatusDto(val rodando: Boolean, val iniciadoEm: String? = null, val concluidoEm: String? = null, val erro: String? = null, val produtos: Int? = null)

    private val status = ConcurrentHashMap<UUID, StatusDto>()

    fun status(tenantId: UUID): StatusDto = status[tenantId] ?: StatusDto(rodando = false)

    /** false = já tem uma rodando pro tenant. */
    fun disparar(tenantSlug: String, tenantId: UUID): Boolean {
        val atual = status[tenantId]
        if (atual?.rodando == true) return false
        val inicio = iso(Instant.now())
        status[tenantId] = StatusDto(rodando = true, iniciadoEm = inicio)
        escopo.launch {
            try {
                TenantTx.run(tenantId) {
                    VolumesAlternativosCacheTable.deleteWhere { VolumesAlternativosCacheTable.tenantId eq tenantId }
                    CodigosBarraCacheTable.deleteWhere { CodigosBarraCacheTable.tenantId eq tenantId }
                    ProdutosCacheTable.deleteWhere { ProdutosCacheTable.tenantId eq tenantId }
                }
                ProdutoCatalogoSyncService.sincronizarTenant(tenantSlug, tenantId)
                val total = ProdutoCatalogoRepository.mapaDtalterProdutos(tenantId).size
                status[tenantId] = StatusDto(rodando = false, iniciadoEm = inicio, concluidoEm = iso(Instant.now()), produtos = total)
            } catch (e: Exception) {
                status[tenantId] = StatusDto(rodando = false, iniciadoEm = inicio, concluidoEm = iso(Instant.now()), erro = e.message ?: "falha")
            }
        }
        return true
    }

    private fun iso(i: Instant) = DateTimeFormatter.ISO_INSTANT.format(i)
}

fun Route.produtoRessincronizacaoRoutes() {
    route("/api/produtos/ressincronizar") {
        post {
            val claims = call.exigirAdmin() ?: return@post
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
            ProdutoRessincronizacao.disparar(slug, claims.tenantId)
            call.respond(HttpStatusCode.Accepted, ProdutoRessincronizacao.status(claims.tenantId))
        }
        get {
            val claims = call.exigirAdmin() ?: return@get
            call.respond(ProdutoRessincronizacao.status(claims.tenantId))
        }
    }
}
